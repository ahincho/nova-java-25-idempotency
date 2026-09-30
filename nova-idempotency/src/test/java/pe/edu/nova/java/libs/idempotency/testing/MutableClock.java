package pe.edu.nova.java.libs.idempotency.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Un reloj que solo avanza cuando la prueba lo mueve, para vencer un lock o una retención sin esperar. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    /** Crea el reloj en un instante fijo de la fecha del ADR. */
    public MutableClock() {
        this(Instant.parse("2026-09-30T12:00:00Z"));
    }

    /**
     * Crea el reloj en un instante.
     *
     * @param start el instante inicial
     */
    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /**
     * Adelanta el reloj.
     *
     * @param amount cuánto
     */
    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
