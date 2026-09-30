package pe.edu.nova.java.libs.idempotency;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Renueva el lock de un intento mientras su operación corre, cada {@code every}, de a una renovación por
 * vez.
 *
 * <p>Así un lock solo vence cuando el proceso que lo tiene murió, y no porque la operación tardó más que
 * {@code lockTtl}. Si el almacén falla, sigue intentando: la renovación siguiente todavía puede llegar
 * antes de que el lock venza. Si el almacén dice que el intento ya no es el dueño, avisa una vez y se
 * detiene. Corre en un hilo virtual, así que no cuesta nada tener uno por operación en curso.
 *
 * <p>Se detiene sin interrumpir el hilo: interrumpir un hilo virtual que espera un socket cierra el socket
 * y, con él, la conexión que estaba usando.
 */
final class Heartbeat implements Runnable {

    private static final System.Logger LOG = System.getLogger(Heartbeat.class.getName());

    private final IdempotencyStore store;
    private final Attempt attempt;
    private final Duration lockTtl;
    private final Duration every;
    private final Duration timeout;
    private final Consumer<Heartbeat> onEnd;
    private final CountDownLatch stopped = new CountDownLatch(1);

    Heartbeat(
            IdempotencyStore store,
            Attempt attempt,
            Duration lockTtl,
            Duration every,
            Duration timeout,
            Consumer<Heartbeat> onEnd) {
        this.store = store;
        this.attempt = attempt;
        this.lockTtl = lockTtl;
        this.every = every;
        this.timeout = timeout;
        this.onEnd = onEnd;
    }

    /** Empieza a renovar. */
    void start() {
        Thread.ofVirtual().name("nova-idempotency-heartbeat").start(this);
    }

    /** Deja de renovar; una renovación que ya está en el almacén termina sola. */
    void stop() {
        stopped.countDown();
    }

    @Override
    public void run() {
        try {
            while (!stopped.await(every.toNanos(), TimeUnit.NANOSECONDS)) {
                if (!renew()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            onEnd.accept(this);
        }
    }

    /**
     * Renueva el lock una vez.
     *
     * @return {@code true} si hay que seguir renovando
     */
    private boolean renew() {
        try {
            if (store.renew(attempt.key(), attempt.owner(), lockTtl, timeout)) {
                return true;
            }
        } catch (RuntimeException failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Attempt {0} could not renew its lock and will try again: {1}",
                    attempt.id(),
                    SafeLog.describe(failure));
            return true;
        }
        // Si el intento ya se está cerrando, que el registro ya no tenga lock es lo esperado.
        if (attempt.running() && attempt.reportLost()) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Attempt {0} lost its lock while the operation was running, so a retry may run the operation "
                            + "again. Check that the store is available and that the lock-ttl setting is long enough.",
                    attempt.id());
        }
        return false;
    }
}
