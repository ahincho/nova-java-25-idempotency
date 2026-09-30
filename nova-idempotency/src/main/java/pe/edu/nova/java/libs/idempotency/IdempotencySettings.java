package pe.edu.nova.java.libs.idempotency;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * La configuración del núcleo, con los valores por defecto de ADR-047.
 *
 * <p>El conector de cada framework las lee de {@code nova.idempotency.*} y arma esta clase; el núcleo no
 * lee ninguna fuente de configuración por su cuenta. Todo tiene un valor por defecto:
 *
 * <ul>
 *   <li>{@code retention}: 24 horas. Cuánto se repite una respuesta guardada.</li>
 *   <li>{@code lock-ttl}: 60 s. Cuánto dura el lock de una operación en curso sin renovarse.</li>
 *   <li>{@code lock-renewal}: 20 s, un tercio del lock. Cada cuánto se renueva mientras la operación
 *       corre; cero lo apaga.</li>
 *   <li>{@code retry-after}: 1 s. Lo que pide esperar el aviso de clave en curso.</li>
 *   <li>{@code store-timeout}: 5 s. Cuánto se espera al almacén en cada llamada.</li>
 *   <li>{@code purge-batch-size}: 1000. Cuántos registros vencidos borra cada lote de la purga.</li>
 *   <li>{@code replay-headers}: ninguno más. Los headers que se repiten además de los seis de
 *       ADR-047.</li>
 * </ul>
 *
 * @param retention          cuánto se conserva y se repite una respuesta guardada
 * @param lockTtl            cuánto dura el lock de una operación en curso sin renovarse
 * @param lockRenewal        cada cuánto se renueva el lock mientras la operación corre; cero no renueva
 * @param retryAfter         cuánto pide esperar el aviso de que la clave sigue en curso
 * @param storeTimeout       cuánto se espera al almacén en cada llamada
 * @param purgeBatchSize     cuántos registros vencidos borra cada lote de la purga
 * @param extraReplayHeaders los headers que se guardan y se repiten además de {@link #DEFAULT_REPLAY_HEADERS}
 */
public record IdempotencySettings(
        Duration retention,
        Duration lockTtl,
        Duration lockRenewal,
        Duration retryAfter,
        Duration storeTimeout,
        int purgeBatchSize,
        Set<String> extraReplayHeaders) {

    /** La retención por defecto de una respuesta: 24 horas, la de Stripe y la de {@code @nestjs/idempotency}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    /** El lock por defecto: 60 segundos, lo que cubre a un proveedor lento sin dejar la clave tomada. */
    public static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(60);

    /** La espera por defecto del aviso de clave en curso: 1 segundo. */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    /** El timeout por defecto de cada llamada al almacén: 5 segundos. */
    public static final Duration DEFAULT_STORE_TIMEOUT = Duration.ofSeconds(5);

    /** El tamaño por defecto de cada lote de la purga: 1000 registros. */
    public static final int DEFAULT_PURGE_BATCH_SIZE = 1000;

    /**
     * Los únicos headers de una respuesta que se guardan y se repiten por defecto (ADR-047): describen el
     * cuerpo guardado.
     */
    public static final Set<String> DEFAULT_REPLAY_HEADERS =
            Set.of("location", "content-type", "content-language", "content-location", "etag", "last-modified");

    /**
     * Los headers que nunca se repiten, ni por configuración: las cookies y las credenciales pertenecen a
     * la respuesta que las escribió, y la plataforma escribe sola los de framing y los de un salto.
     */
    private static final Set<String> NEVER_REPLAYED = Set.of(
            "set-cookie",
            "set-cookie2",
            "cookie",
            "authorization",
            "proxy-authorization",
            "www-authenticate",
            "proxy-authenticate",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "upgrade",
            "trailer",
            "te",
            "date");

    private static final String CORS_PREFIX = "access-control-";
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9a-z-]+");
    private static final int LOCK_RENEWALS_PER_TTL = 3;

    /**
     * Crea la configuración y la valida.
     *
     * @throws IllegalArgumentException si una duración no es positiva, si la renovación no es más corta que
     *                                  el lock, si el lote es menor que 1 o si un header extra es de los que
     *                                  nunca se repiten
     */
    public IdempotencySettings {
        requirePositive(retention, "retention");
        requirePositive(lockTtl, "lockTtl");
        requirePositive(retryAfter, "retryAfter");
        requirePositive(storeTimeout, "storeTimeout");
        Objects.requireNonNull(lockRenewal, "lockRenewal");
        if (lockRenewal.isNegative()) {
            throw new IllegalArgumentException("lockRenewal must not be negative");
        }
        if (!lockRenewal.isZero() && lockRenewal.compareTo(lockTtl) >= 0) {
            throw new IllegalArgumentException("lockRenewal must be shorter than lockTtl, or the lock could expire");
        }
        if (purgeBatchSize < 1) {
            throw new IllegalArgumentException("purgeBatchSize must be at least 1");
        }
        extraReplayHeaders = normalizeHeaders(Objects.requireNonNull(extraReplayHeaders, "extraReplayHeaders"));
    }

    /**
     * La configuración con todos los valores por defecto.
     *
     * @return la configuración
     */
    public static IdempotencySettings defaults() {
        return new IdempotencySettings(
                DEFAULT_RETENTION,
                DEFAULT_LOCK_TTL,
                DEFAULT_LOCK_TTL.dividedBy(LOCK_RENEWALS_PER_TTL),
                DEFAULT_RETRY_AFTER,
                DEFAULT_STORE_TIMEOUT,
                DEFAULT_PURGE_BATCH_SIZE,
                Set.of());
    }

    /**
     * La misma configuración con otra retención.
     *
     * @param retention cuánto se repite una respuesta guardada
     * @return la configuración nueva
     */
    public IdempotencySettings withRetention(Duration retention) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, extraReplayHeaders);
    }

    /**
     * La misma configuración con otro lock. La renovación pasa a ser un tercio del lock nuevo; si se quiere
     * otra, se fija después con {@link #withLockRenewal}.
     *
     * @param lockTtl cuánto dura el lock sin renovarse
     * @return la configuración nueva
     */
    public IdempotencySettings withLockTtl(Duration lockTtl) {
        Objects.requireNonNull(lockTtl, "lockTtl");
        return new IdempotencySettings(
                retention,
                lockTtl,
                lockTtl.dividedBy(LOCK_RENEWALS_PER_TTL),
                retryAfter,
                storeTimeout,
                purgeBatchSize,
                extraReplayHeaders);
    }

    /**
     * La misma configuración con otra renovación del lock. Cero la apaga: el lock vence a los
     * {@code lockTtl} y la operación no lo mantiene vivo, algo que solo tiene sentido si la operación
     * siempre dura menos que el lock.
     *
     * @param lockRenewal cada cuánto se renueva el lock
     * @return la configuración nueva
     */
    public IdempotencySettings withLockRenewal(Duration lockRenewal) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, extraReplayHeaders);
    }

    /**
     * La misma configuración con otra espera para el aviso de clave en curso.
     *
     * @param retryAfter lo que pide esperar el 409
     * @return la configuración nueva
     */
    public IdempotencySettings withRetryAfter(Duration retryAfter) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, extraReplayHeaders);
    }

    /**
     * La misma configuración con otro timeout para el almacén.
     *
     * @param storeTimeout cuánto se espera al almacén en cada llamada
     * @return la configuración nueva
     */
    public IdempotencySettings withStoreTimeout(Duration storeTimeout) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, extraReplayHeaders);
    }

    /**
     * La misma configuración con otro tamaño de lote para la purga.
     *
     * @param purgeBatchSize cuántos registros vencidos borra cada lote
     * @return la configuración nueva
     */
    public IdempotencySettings withPurgeBatchSize(int purgeBatchSize) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, extraReplayHeaders);
    }

    /**
     * La misma configuración con otros headers que se repiten además de los seis por defecto.
     *
     * @param headers los nombres de los headers; no pueden ser cookies, credenciales, headers de CORS ni de
     *                framing
     * @return la configuración nueva
     */
    public IdempotencySettings withReplayHeaders(Collection<String> headers) {
        return new IdempotencySettings(
                retention, lockTtl, lockRenewal, retryAfter, storeTimeout, purgeBatchSize, Set.copyOf(headers));
    }

    /**
     * Todos los headers que se guardan y se repiten: los seis por defecto y los que se agregaron.
     *
     * @return los nombres, en minúscula
     */
    public Set<String> replayHeaders() {
        Set<String> all = new LinkedHashSet<>(DEFAULT_REPLAY_HEADERS);
        all.addAll(extraReplayHeaders);
        return Set.copyOf(all);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Set<String> normalizeHeaders(Collection<String> names) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            String header = Objects.requireNonNull(name, "header name").strip().toLowerCase(Locale.ROOT);
            if (!TOKEN.matcher(header).matches()) {
                throw new IllegalArgumentException("A replay header must be a valid header name");
            }
            if (NEVER_REPLAYED.contains(header) || header.startsWith(CORS_PREFIX)) {
                throw new IllegalArgumentException("The header " + header
                        + " is never replayed: cookies, credentials, CORS and framing headers are excluded");
            }
            normalized.add(header);
        }
        return Set.copyOf(normalized);
    }
}
