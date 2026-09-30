package pe.edu.nova.java.libs.idempotency.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;
import pe.edu.nova.java.libs.idempotency.testing.IdempotencyStoreContract;
import pe.edu.nova.java.libs.idempotency.testing.MutableClock;

/** El almacén en memoria cumple el contrato de todos los almacenes, y se declara no persistente. */
class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    protected IdempotencyStore createStore(Clock clock) {
        return new InMemoryIdempotencyStore(clock);
    }

    @Override
    protected boolean expectedPersistent() {
        return false;
    }

    @Test
    void theDefaultStoreMeasuresTimeWithTheSystemClock() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        ScopedKey key = new ScopedKey("scope", "k");
        OwnerToken owner = OwnerToken.generate();

        assertThat(store.acquire(key, owner, "fp", TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(store.complete(key, owner, StoredResponse.of(200, new byte[0]), TTL, TIMEOUT))
                .isTrue();
        assertThat(store.acquire(key, OwnerToken.generate(), "fp", TTL, TIMEOUT))
                .isInstanceOf(Acquisition.Completed.class);
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void expiredRecordsAreReusedAndOnlyThePurgeReleasesTheirMemory() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
        for (int i = 0; i < 5; i++) {
            store.acquire(new ScopedKey("scope", "k" + i), OwnerToken.generate(), "fp", TTL, TIMEOUT);
        }
        clock.advance(Duration.ofMinutes(2));

        assertThat(store.size()).as("expired, but still in memory").isEqualTo(5);
        assertThat(store.acquire(new ScopedKey("scope", "k0"), OwnerToken.generate(), "fp2", TTL, TIMEOUT))
                .as("an expired record is reused")
                .isEqualTo(new Acquisition.Acquired());
        assertThat(store.purgeExpired(100, TIMEOUT)).isEqualTo(4);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aClockIsRequired() {
        assertThatThrownBy(() -> new InMemoryIdempotencyStore(null)).isInstanceOf(NullPointerException.class);
    }
}
