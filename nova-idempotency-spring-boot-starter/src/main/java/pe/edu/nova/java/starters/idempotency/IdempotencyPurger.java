package pe.edu.nova.java.starters.idempotency;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;

/**
 * Purga los registros vencidos cada cierto tiempo, en un hilo propio. Un registro vencido ya se ignora y se
 * reutiliza, así que la purga solo libera espacio; varias réplicas pueden purgar a la vez sin esperarse.
 */
final class IdempotencyPurger implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(IdempotencyPurger.class.getName());

    private final IdempotencyEngine engine;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private boolean running;

    IdempotencyPurger(IdempotencyEngine engine, Duration interval) {
        this.engine = engine;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        running = true;
        if (interval.isZero()) {
            // La purga programada está apagada; el servicio puede llamar a purgeExpired por su cuenta.
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("nova-idempotency-purge").daemon().factory());
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::purge, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    void purge() {
        try {
            engine.purgeExpired();
        } catch (RuntimeException failure) {
            // La siguiente vuelta lo vuelve a intentar; el error del almacén no cita claves ni cuerpos.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Could not purge the expired idempotency records: {0}",
                    failure.getMessage());
        }
    }
}
