package pe.edu.nova.java.libs.idempotency.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;

/**
 * El almacén de un solo proceso: los registros viven en un mapa concurrente.
 *
 * <p>Cada operación es atómica por clave, porque corre dentro de una operación del mapa. Dos peticiones
 * simultáneas de una misma instancia nunca ganan las dos la misma clave. Mide el tiempo con un
 * {@link Clock}, así que una prueba puede adelantar el reloj para vencer un lock o una retención.
 *
 * <p><strong>No es persistente</strong> ({@link #persistent()} devuelve {@code false}): los registros se
 * pierden al reiniciar y no los comparten varias instancias. Sirve para desarrollo y pruebas; en
 * producción hace falta un almacén compartido, como el de JDBC. Los registros vencidos se reutilizan al
 * pedir su clave, pero solo {@link #purgeExpired} los borra.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private sealed interface Entry {
        String fingerprint();

        Instant expiresAt();
    }

    private record InFlight(String fingerprint, OwnerToken owner, Instant expiresAt) implements Entry {}

    private record Done(String fingerprint, StoredResponse response, Instant expiresAt) implements Entry {}

    private final Map<ScopedKey, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Crea un almacén que mide el tiempo con el reloj del sistema. */
    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    /**
     * Crea un almacén con el reloj que se le da.
     *
     * @param clock el reloj con que se vencen los locks y las respuestas
     */
    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Acquisition acquire(
            ScopedKey key, OwnerToken owner, String fingerprint, Duration lockTtl, Duration timeout) {
        Instant now = clock.instant();
        Entry mine = new InFlight(fingerprint, owner, now.plus(lockTtl));
        Entry current = entries.compute(key, (ignored, existing) -> isLive(existing, now) ? existing : mine);
        if (current == mine) {
            return new Acquisition.Acquired();
        }
        return switch (current) {
            case InFlight inFlight -> new Acquisition.InFlight(inFlight.fingerprint());
            case Done done -> new Acquisition.Completed(done.fingerprint(), done.response());
        };
    }

    @Override
    public boolean complete(
            ScopedKey key, OwnerToken owner, StoredResponse response, Duration retention, Duration timeout) {
        Instant expiresAt = clock.instant().plus(retention);
        AtomicBoolean done = new AtomicBoolean();
        entries.computeIfPresent(key, (ignored, existing) -> {
            if (existing instanceof InFlight inFlight && inFlight.owner().equals(owner)) {
                done.set(true);
                return new Done(inFlight.fingerprint(), response, expiresAt);
            }
            return existing;
        });
        return done.get();
    }

    @Override
    public boolean release(ScopedKey key, OwnerToken owner, Duration timeout) {
        AtomicBoolean released = new AtomicBoolean();
        entries.computeIfPresent(key, (ignored, existing) -> {
            if (existing instanceof InFlight inFlight && inFlight.owner().equals(owner)) {
                released.set(true);
                return null;
            }
            return existing;
        });
        return released.get();
    }

    @Override
    public boolean renew(ScopedKey key, OwnerToken owner, Duration lockTtl, Duration timeout) {
        Instant expiresAt = clock.instant().plus(lockTtl);
        AtomicBoolean renewed = new AtomicBoolean();
        entries.computeIfPresent(key, (ignored, existing) -> {
            if (existing instanceof InFlight inFlight && inFlight.owner().equals(owner)) {
                renewed.set(true);
                return new InFlight(inFlight.fingerprint(), owner, expiresAt);
            }
            return existing;
        });
        return renewed.get();
    }

    @Override
    public int purgeExpired(int limit, Duration timeout) {
        Instant now = clock.instant();
        int purged = 0;
        Iterator<Map.Entry<ScopedKey, Entry>> candidates = entries.entrySet().iterator();
        while (purged < limit && candidates.hasNext()) {
            Map.Entry<ScopedKey, Entry> candidate = candidates.next();
            // remove(clave, valor) no borra un registro que otro intento tomó mientras se recorría el mapa.
            if (!isLive(candidate.getValue(), now) && entries.remove(candidate.getKey(), candidate.getValue())) {
                purged++;
            }
        }
        return purged;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    /** Cuántos registros hay, vencidos o no. Para las pruebas. */
    int size() {
        return entries.size();
    }

    private static boolean isLive(Entry entry, Instant now) {
        return entry != null && entry.expiresAt().isAfter(now);
    }
}
