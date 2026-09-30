package pe.edu.nova.java.libs.idempotency;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * El motor de la capacidad de idempotencia (ADR-047): con la clave, el alcance y la huella de una
 * petición, decide qué hacer con ella.
 *
 * <p>El motor no habla HTTP y devuelve resultados; el conector de cada framework los traduce. Una
 * petición recorre este camino:
 *
 * <pre>{@code
 * IdempotencyEngine engine = new IdempotencyEngine(store);
 *
 * switch (engine.begin(key, scope, fingerprint)) {
 *     case Decision.Execute execute -> {
 *         StoredResponse response = runTheOperation();
 *         engine.complete(execute.attempt(), response);
 *     }
 *     case Decision.Replay replay -> send(replay.response());
 *     case Decision.InProgress inProgress -> conflict(inProgress.retryAfterSeconds());
 *     case Decision.KeyReused reused -> unprocessable();
 *     case Decision.KeyMissing missing -> badRequest();
 *     case Decision.KeyInvalid invalid -> badRequest();
 * }
 * }</pre>
 *
 * <p>Si la operación termina sin una respuesta que guardar, por ejemplo con una excepción, el conector
 * llama a {@link #release} para que un reintento pueda ejecutarla. Mientras la operación corre, el motor
 * renueva el lock solo, en un hilo virtual por operación, hasta que el conector la completa o la libera.
 *
 * <p>Las reglas que el motor aplica y ningún almacén cambia:
 *
 * <ul>
 *   <li>el alcance siempre es parte de la clave, y un alcance vacío se rechaza;
 *   <li>un 5xx nunca se guarda: libera la clave para que el cliente pueda reintentar;
 *   <li>de los headers solo se guardan y se repiten los de la configuración, nunca cookies ni
 *       credenciales, y el motor lo vuelve a comprobar al repetir;
 *   <li>ni la clave ni el cuerpo aparecen en un log;
 *   <li>toda llamada al almacén lleva el timeout de la configuración.
 * </ul>
 *
 * <p>Es seguro usarlo desde varios hilos. Al terminar el servicio se cierra con {@link #close()}, que
 * detiene las renovaciones que sigan en curso.
 */
public final class IdempotencyEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IdempotencyEngine.class.getName());

    private static final int MAX_FINGERPRINT_LENGTH = 255;
    private static final Decision KEY_MISSING = new Decision.KeyMissing();
    private static final Decision KEY_INVALID = new Decision.KeyInvalid();
    private static final Decision KEY_REUSED = new Decision.KeyReused();

    private final IdempotencyStore store;
    private final IdempotencySettings settings;
    private final Set<String> replayHeaders;
    private final Set<Heartbeat> heartbeats = ConcurrentHashMap.newKeySet();

    /**
     * Crea el motor con la configuración por defecto.
     *
     * @param store el almacén de los registros
     */
    public IdempotencyEngine(IdempotencyStore store) {
        this(store, IdempotencySettings.defaults());
    }

    /**
     * Crea el motor.
     *
     * @param store    el almacén de los registros
     * @param settings la configuración
     */
    public IdempotencyEngine(IdempotencyStore store, IdempotencySettings settings) {
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.replayHeaders = settings.replayHeaders();
    }

    /**
     * Decide qué hacer con una petición.
     *
     * <p>Si la clave es válida, intenta tomarla en el almacén con una sola operación atómica, y según lo que
     * encuentre devuelve:
     *
     * <ul>
     *   <li>{@link Decision.Execute}, si la clave estaba libre o vencida: la operación es de este intento;
     *   <li>{@link Decision.KeyReused}, si tenía un registro con otra huella, esté en curso o completado;
     *   <li>{@link Decision.InProgress}, si tenía un lock vigente con la misma huella;
     *   <li>{@link Decision.Replay}, si tenía una respuesta guardada con la misma huella.
     * </ul>
     *
     * <p>Una clave que falta ({@code null} o vacía) es {@link Decision.KeyMissing}, y una que no tiene entre
     * 1 y 255 caracteres ASCII imprimibles, {@link Decision.KeyInvalid}. La clave se toma tal cual llega: no
     * se recorta ni se le quitan las comillas.
     *
     * @param key         la clave que mandó el cliente, o {@code null} si no mandó ninguna
     * @param scope       de quién es la clave; no puede ser vacío, porque no existe una clave global
     * @param fingerprint la huella de la petición, de 1 a 255 caracteres
     * @return lo que hay que hacer
     * @throws IllegalArgumentException  si el alcance está vacío o es inválido, o si la huella no sirve;
     *                                   son errores de quien llama, no del cliente, y el mensaje no cita los
     *                                   valores
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada; la operación no debe correr
     */
    public Decision begin(String key, String scope, String fingerprint) {
        ScopedKey.checkScope(scope);
        requireFingerprint(fingerprint);
        if (key == null || key.isEmpty()) {
            return KEY_MISSING;
        }
        if (!IdempotencyKeys.isValid(key)) {
            return KEY_INVALID;
        }

        ScopedKey scoped = new ScopedKey(scope, key);
        OwnerToken owner = OwnerToken.generate();
        Acquisition acquisition;
        try {
            acquisition = store.acquire(scoped, owner, fingerprint, settings.lockTtl(), settings.storeTimeout());
        } catch (RuntimeException failure) {
            // El almacén pudo tomar el lock antes de perder la respuesta. Soltarlo, que solo lo suelta si es de
            // este intento, ahorra al reintento esperar a que venza. Corre aparte para no sumar otro timeout a
            // la petición que ya está fallando.
            Thread.ofVirtual().name("nova-idempotency-release").start(() -> releaseQuietly(scoped, owner));
            throw failure;
        }
        Objects.requireNonNull(acquisition, "The store returned no result for acquire");

        return switch (acquisition) {
            case Acquisition.Acquired acquired -> execute(scoped, owner);
            case Acquisition.InFlight inFlight ->
                inFlight.fingerprint().equals(fingerprint)
                        ? new Decision.InProgress(settings.retryAfter())
                        : KEY_REUSED;
            case Acquisition.Completed completed ->
                completed.fingerprint().equals(fingerprint)
                        ? new Decision.Replay(replayable(completed.response()))
                        : KEY_REUSED;
        };
    }

    /**
     * Guarda la respuesta de un intento y libera a los reintentos para que la reciban.
     *
     * <p>Solo se guardan el status, el cuerpo y los headers permitidos. <strong>Un 5xx no se guarda</strong>:
     * libera la clave, para que el cliente pueda reintentar y la operación se ejecute de nuevo.
     *
     * <p>No lanza si el almacén falla: la operación ya ocurrió, y fallarle al cliente ahora lo invita a hacer
     * justo el reintento que esta capacidad quiere evitar. El fallo queda en el log y el resultado es
     * {@link Completion#FAILED}.
     *
     * @param attempt  el intento que ganó la clave
     * @param response la respuesta de la operación
     * @return cómo quedó el registro
     */
    public Completion complete(Attempt attempt, StoredResponse response) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(response, "response");
        if (response.status() >= 500) {
            return release(attempt);
        }
        StoredResponse storable = replayable(response);
        return finish(
                attempt,
                "store the response",
                () -> store.complete(
                                attempt.key(), attempt.owner(), storable, settings.retention(), settings.storeTimeout())
                        ? Completion.STORED
                        : Completion.LOST);
    }

    /**
     * Libera la clave de un intento sin guardar nada, para que un reintento pueda ejecutar la operación.
     * Es lo que hace el conector cuando la operación termina con una excepción.
     *
     * <p>No lanza si el almacén falla, igual que {@link #complete}.
     *
     * @param attempt el intento que ganó la clave
     * @return {@link Completion#RELEASED} si la clave quedó libre
     */
    public Completion release(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return finish(
                attempt,
                "release the key",
                () -> store.release(attempt.key(), attempt.owner(), settings.storeTimeout())
                        ? Completion.RELEASED
                        : Completion.LOST);
    }

    /**
     * Borra los registros vencidos, por lotes del tamaño de la configuración, hasta que no queden.
     *
     * <p>Un registro vencido ya se ignora y se reutiliza, así que esto solo libera espacio. Conviene correrlo
     * desde una tarea programada.
     *
     * @return cuántos registros borró
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    public int purgeExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = store.purgeExpired(settings.purgeBatchSize(), settings.storeTimeout());
            total += deleted;
        } while (deleted >= settings.purgeBatchSize());
        if (total > 0) {
            LOG.log(System.Logger.Level.INFO, "Purged {0} expired idempotency records", total);
        }
        return total;
    }

    /** Detiene las renovaciones de los intentos que sigan corriendo. Es seguro llamarlo más de una vez. */
    @Override
    public void close() {
        heartbeats.forEach(Heartbeat::stop);
    }

    private Decision execute(ScopedKey key, OwnerToken owner) {
        Attempt attempt = new Attempt(key, owner);
        if (!settings.lockRenewal().isZero()) {
            Heartbeat heartbeat = new Heartbeat(
                    store,
                    attempt,
                    settings.lockTtl(),
                    settings.lockRenewal(),
                    settings.storeTimeout(),
                    heartbeats::remove);
            heartbeats.add(heartbeat);
            attempt.heartbeat(heartbeat);
            heartbeat.start();
        }
        return new Decision.Execute(attempt);
    }

    /**
     * Cierra un intento con una operación del almacén, una sola vez. Mientras el almacén contesta, el latido
     * sigue renovando el lock.
     */
    private Completion finish(Attempt attempt, String action, Supplier<Completion> operation) {
        if (!attempt.startClosing()) {
            Completion previous = attempt.outcome();
            return previous != null ? previous : Completion.LOST;
        }
        Completion result = Completion.FAILED;
        try {
            result = operation.get();
            if (result == Completion.LOST && attempt.reportLost()) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Attempt {0} was no longer the owner of its key when it tried to {1}, so a retry may have "
                                + "run the operation again",
                        attempt.id(),
                        action);
            }
        } catch (RuntimeException failure) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Attempt {0} could not {1}, and its key stays taken until the lock expires: {2}",
                    attempt.id(),
                    action,
                    SafeLog.describe(failure));
        } finally {
            attempt.closed(result);
        }
        return result;
    }

    private void releaseQuietly(ScopedKey key, OwnerToken owner) {
        try {
            store.release(key, owner, settings.storeTimeout());
        } catch (RuntimeException ignored) {
            // Es un intento de limpiar después de un fallo que ya se va a lanzar; si también falla, el lock vence.
        }
    }

    /** Deja solo los headers que se pueden repetir; si ya eran todos permitidos, devuelve la misma respuesta. */
    private StoredResponse replayable(StoredResponse stored) {
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        stored.headers().forEach((name, values) -> {
            if (replayHeaders.contains(name)) {
                allowed.put(name, values);
            }
        });
        if (allowed.size() == stored.headers().size()) {
            return stored;
        }
        return new StoredResponse(stored.status(), allowed, stored.body());
    }

    private static void requireFingerprint(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.isEmpty() || fingerprint.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException(
                    "The fingerprint must have 1 to " + MAX_FINGERPRINT_LENGTH + " characters");
        }
    }
}
