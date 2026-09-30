package pe.edu.nova.java.libs.idempotency;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Un intento de ejecutar la operación: quien ganó la clave y tiene que completarla o liberarla.
 *
 * <p>Es un asa opaca. La entrega {@link IdempotencyEngine#begin} dentro de un {@link Decision.Execute} y
 * el conector solo se la devuelve al núcleo. Lleva la clave, el token de dueño, que es distinto en cada
 * intento, y el estado del latido que mantiene vivo el lock mientras la operación corre.
 *
 * <p>Su {@link #toString()} solo muestra un identificador aleatorio de correlación, que es lo que aparece
 * en el log: ni la clave ni el alcance ni el token.
 */
public final class Attempt {

    private enum Phase {
        RUNNING,
        CLOSING,
        CLOSED
    }

    private final ScopedKey key;
    private final OwnerToken owner;
    private final String id =
            HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    private final AtomicBoolean lostReported = new AtomicBoolean();
    private volatile Completion outcome;
    private volatile Heartbeat heartbeat;

    Attempt(ScopedKey key, OwnerToken owner) {
        this.key = key;
        this.owner = owner;
    }

    ScopedKey key() {
        return key;
    }

    OwnerToken owner() {
        return owner;
    }

    /** El identificador de correlación del log. */
    String id() {
        return id;
    }

    void heartbeat(Heartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    /** Dice si la operación sigue corriendo: nadie la completó ni la liberó todavía. */
    boolean running() {
        return phase.get() == Phase.RUNNING;
    }

    /**
     * Empieza a cerrar el intento. El latido sigue renovando el lock hasta que el almacén contesta, para que
     * un guardado lento no deje vencer el lock y un reintento ejecute la operación otra vez.
     *
     * @return {@code true} para la primera llamada; {@code false} si otra ya cerró o está cerrando
     */
    boolean startClosing() {
        return phase.compareAndSet(Phase.RUNNING, Phase.CLOSING);
    }

    /** Termina el intento y detiene su latido. */
    void closed(Completion result) {
        outcome = result;
        phase.set(Phase.CLOSED);
        Heartbeat current = heartbeat;
        if (current != null) {
            current.stop();
        }
    }

    /** Cómo terminó, o {@code null} si todavía no. */
    Completion outcome() {
        return outcome;
    }

    /**
     * Anota que se avisó de la pérdida del lock, para avisar una sola vez por intento.
     *
     * @return {@code true} si es la primera vez
     */
    boolean reportLost() {
        return lostReported.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "Attempt[" + id + "]";
    }
}
