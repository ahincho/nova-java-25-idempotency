package pe.edu.nova.java.libs.idempotency.testing;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;

/**
 * Un almacén que delega en otro, cuenta las llamadas y falla cuando la prueba se lo pide.
 *
 * <p>Sirve para comprobar lo que el núcleo hace cuando el almacén falla, cuántas veces renueva el latido y
 * qué escribe en el log.
 */
public final class RecordingStore implements IdempotencyStore {

    private final IdempotencyStore delegate;
    private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();
    private final AtomicInteger acquires = new AtomicInteger();
    private final AtomicInteger completes = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicInteger renews = new AtomicInteger();
    private final AtomicInteger purges = new AtomicInteger();
    private volatile boolean loseRenewals;

    /**
     * Crea el almacén.
     *
     * @param delegate el almacén verdadero
     */
    public RecordingStore(IdempotencyStore delegate) {
        this.delegate = delegate;
    }

    /**
     * Hace que una operación lance una excepción, sin llegar al almacén verdadero.
     *
     * @param operation {@code acquire}, {@code complete}, {@code release}, {@code renew} o {@code purge}
     * @param failure   lo que lanza
     */
    public void failOn(String operation, RuntimeException failure) {
        failures.put(operation, failure);
    }

    /** Deja de fallar: las operaciones vuelven a llegar al almacén verdadero. */
    public void recover() {
        failures.clear();
    }

    /** Hace que {@code renew} conteste que el intento ya no es el dueño, sin llegar al almacén verdadero. */
    public void loseRenewals() {
        loseRenewals = true;
    }

    /**
     * Las llamadas a {@code acquire}.
     *
     * @return cuántas
     */
    public int acquires() {
        return acquires.get();
    }

    /**
     * Las llamadas a {@code complete}.
     *
     * @return cuántas
     */
    public int completes() {
        return completes.get();
    }

    /**
     * Las llamadas a {@code release}.
     *
     * @return cuántas
     */
    public int releases() {
        return releases.get();
    }

    /**
     * Las llamadas a {@code renew}.
     *
     * @return cuántas
     */
    public int renews() {
        return renews.get();
    }

    /**
     * Las llamadas a {@code purgeExpired}.
     *
     * @return cuántas
     */
    public int purges() {
        return purges.get();
    }

    @Override
    public Acquisition acquire(
            ScopedKey key, OwnerToken owner, String fingerprint, Duration lockTtl, Duration timeout) {
        acquires.incrementAndGet();
        failIfAsked("acquire");
        return delegate.acquire(key, owner, fingerprint, lockTtl, timeout);
    }

    @Override
    public boolean complete(
            ScopedKey key, OwnerToken owner, StoredResponse response, Duration retention, Duration timeout) {
        completes.incrementAndGet();
        failIfAsked("complete");
        return delegate.complete(key, owner, response, retention, timeout);
    }

    @Override
    public boolean release(ScopedKey key, OwnerToken owner, Duration timeout) {
        releases.incrementAndGet();
        failIfAsked("release");
        return delegate.release(key, owner, timeout);
    }

    @Override
    public boolean renew(ScopedKey key, OwnerToken owner, Duration lockTtl, Duration timeout) {
        renews.incrementAndGet();
        failIfAsked("renew");
        if (loseRenewals) {
            return false;
        }
        return delegate.renew(key, owner, lockTtl, timeout);
    }

    @Override
    public int purgeExpired(int limit, Duration timeout) {
        purges.incrementAndGet();
        failIfAsked("purge");
        return delegate.purgeExpired(limit, timeout);
    }

    @Override
    public boolean persistent() {
        return delegate.persistent();
    }

    private void failIfAsked(String operation) {
        RuntimeException failure = failures.get(operation);
        if (failure != null) {
            throw failure;
        }
    }
}
