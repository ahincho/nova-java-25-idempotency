package pe.edu.nova.java.libs.idempotency.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;

/**
 * El contrato de {@link IdempotencyStore} como casos de prueba: lo que debe cumplir cualquier almacén.
 *
 * <p>Corre igual contra el almacén en memoria y contra el de JDBC, y sirve para el de cualquier
 * organización: basta con extender la clase e implementar {@link #createStore}. El almacén mide el tiempo con
 * el reloj que se le da, así que los casos de vencimiento no esperan: adelantan {@link #clock}.
 *
 * <p>Cada caso usa un alcance propio, de modo que todos pueden compartir un almacén y una tabla. Los que
 * borran vencidos, en cambio, ven toda la tabla: quien la comparte entre casos la vacía antes de cada uno.
 */
public abstract class IdempotencyStoreContract {

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MARGIN = Duration.ofSeconds(1);
    private static final int CALLERS = 16;

    /** La misma letra en las dos formas de Unicode, que para el almacén son dos textos distintos. */
    private static final String PRECOMPOSED = new String(Character.toChars(0xE9));

    private static final String DECOMPOSED = "e" + new String(Character.toChars(0x301));

    private static final OwnerToken OWNER_A = new OwnerToken("owner-a");
    private static final OwnerToken OWNER_B = new OwnerToken("owner-b");
    private static final OwnerToken OWNER_C = new OwnerToken("owner-c");

    /** Un alcance distinto por caso: JUnit crea una instancia nueva por cada método de prueba. */
    private final String scope = "scope-" + UUID.randomUUID();

    /** El reloj del almacén, que solo avanza cuando el caso lo mueve. */
    protected MutableClock clock;

    private IdempotencyStore store;

    /**
     * Crea el almacén que se prueba.
     *
     * @param clock el reloj con que debe medir el tiempo
     * @return el almacén, vacío o compartido con otros casos
     */
    protected abstract IdempotencyStore createStore(Clock clock);

    /**
     * Dice si el almacén debe declararse persistente.
     *
     * @return {@code true} si sobrevive a un reinicio y lo comparten las réplicas
     */
    protected abstract boolean expectedPersistent();

    @BeforeEach
    protected void openStore() {
        clock = new MutableClock();
        store = createStore(clock);
    }

    @Test
    public void aFreeKeyIsAcquiredAndLaterCallersSeeTheLockWithItsFingerprint() {
        ScopedKey key = key("k");

        assertThat(store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("the stored fingerprint, not the caller's")
                .isEqualTo(new Acquisition.InFlight("fp-a"));
        assertThat(store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT))
                .as("a lock is taken once, even by its own owner")
                .isEqualTo(new Acquisition.InFlight("fp-a"));
    }

    @Test
    public void completeStoresTheResponseForTheOwnerOnly() {
        ScopedKey key = key("k");
        StoredResponse response = created("{\"id\":1}");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);

        assertThat(store.complete(key, OWNER_B, response, RETENTION, TIMEOUT))
                .as("another owner")
                .isFalse();
        assertThat(store.complete(key, OWNER_A, response, RETENTION, TIMEOUT))
                .as("the owner")
                .isTrue();

        assertThat(store.acquire(key, OWNER_C, "fp-c", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-a", response));
    }

    @Test
    public void aCompletedRecordIsFinal() {
        ScopedKey key = key("k");
        StoredResponse response = created("{\"id\":1}");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);
        store.complete(key, OWNER_A, response, RETENTION, TIMEOUT);

        assertThat(store.complete(key, OWNER_A, created("overwritten"), RETENTION, TIMEOUT))
                .isFalse();
        assertThat(store.release(key, OWNER_A, TIMEOUT)).isFalse();
        assertThat(store.renew(key, OWNER_A, LOCK_TTL, TIMEOUT)).isFalse();

        assertThat(store.acquire(key, OWNER_B, "fp-a", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-a", response));
    }

    @Test
    public void releaseDeletesTheLockForTheOwnerOnlyAndFreesTheKey() {
        ScopedKey key = key("k");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);

        assertThat(store.release(key, OWNER_B, TIMEOUT)).as("another owner").isFalse();
        assertThat(store.release(key, OWNER_A, TIMEOUT)).as("the owner").isTrue();
        assertThat(store.release(key, OWNER_A, TIMEOUT)).as("twice").isFalse();

        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(store.acquire(key, OWNER_C, "fp-c", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.InFlight("fp-b"));
    }

    @Test
    public void operationsOnAMissingKeyChangeNothing() {
        ScopedKey key = key("missing");

        assertThat(store.complete(key, OWNER_A, created("x"), RETENTION, TIMEOUT))
                .isFalse();
        assertThat(store.renew(key, OWNER_A, LOCK_TTL, TIMEOUT)).isFalse();
        assertThat(store.release(key, OWNER_A, TIMEOUT)).isFalse();

        assertThat(store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
    }

    @Test
    public void renewExtendsTheLockForTheOwnerOnly() {
        ScopedKey key = key("k");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);

        clock.advance(LOCK_TTL.minus(MARGIN));
        assertThat(store.renew(key, OWNER_B, LOCK_TTL, TIMEOUT))
                .as("another owner")
                .isFalse();
        assertThat(store.renew(key, OWNER_A, LOCK_TTL, TIMEOUT)).as("the owner").isTrue();

        clock.advance(LOCK_TTL.minus(MARGIN));
        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("past the first expiry, before the renewed one")
                .isEqualTo(new Acquisition.InFlight("fp-a"));

        clock.advance(MARGIN.multipliedBy(2));
        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("past the renewed expiry")
                .isEqualTo(new Acquisition.Acquired());
    }

    @Test
    public void renewLeavesTheRetentionOfACompletedRecordAlone() {
        ScopedKey key = key("k");
        StoredResponse response = created("{}");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);
        store.complete(key, OWNER_A, response, RETENTION, TIMEOUT);
        assertThat(store.renew(key, OWNER_A, LOCK_TTL, TIMEOUT)).isFalse();

        clock.advance(RETENTION.minus(MARGIN));
        assertThat(store.acquire(key, OWNER_B, "fp-a", LOCK_TTL, TIMEOUT))
                .as("the record keeps the retention, not the lock ttl")
                .isEqualTo(new Acquisition.Completed("fp-a", response));

        clock.advance(MARGIN.multipliedBy(2));
        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
    }

    @Test
    public void anExpiredLockIsTakenOverAndTheStaleOwnerIsFencedOff() {
        ScopedKey key = key("k");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);
        clock.advance(LOCK_TTL.plus(MARGIN));

        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("the retry takes the expired lock")
                .isEqualTo(new Acquisition.Acquired());
        assertThat(store.renew(key, OWNER_A, LOCK_TTL, TIMEOUT))
                .as("stale renew")
                .isFalse();
        assertThat(store.complete(key, OWNER_A, created("stale"), RETENTION, TIMEOUT))
                .as("stale complete")
                .isFalse();
        assertThat(store.release(key, OWNER_A, TIMEOUT)).as("stale release").isFalse();

        assertThat(store.acquire(key, OWNER_C, "fp-c", LOCK_TTL, TIMEOUT))
                .as("the new owner's lock is untouched")
                .isEqualTo(new Acquisition.InFlight("fp-b"));
        StoredResponse fresh = created("fresh");
        assertThat(store.complete(key, OWNER_B, fresh, RETENTION, TIMEOUT))
                .as("the new owner")
                .isTrue();
        assertThat(store.acquire(key, OWNER_C, "fp-b", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-b", fresh));
    }

    @Test
    public void anExpiredLockStillBelongsToItsOwnerUntilSomeoneTakesIt() {
        StoredResponse response = created("late");
        ScopedKey toComplete = key("to-complete");
        ScopedKey toRelease = key("to-release");
        ScopedKey toRenew = key("to-renew");
        for (ScopedKey key : List.of(toComplete, toRelease, toRenew)) {
            store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);
        }
        clock.advance(LOCK_TTL.plus(MARGIN));

        assertThat(store.complete(toComplete, OWNER_A, response, RETENTION, TIMEOUT))
                .as("a slow attempt that nobody replaced can still store its response")
                .isTrue();
        assertThat(store.acquire(toComplete, OWNER_B, "fp-a", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-a", response));

        assertThat(store.release(toRelease, OWNER_A, TIMEOUT)).isTrue();
        assertThat(store.acquire(toRelease, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());

        assertThat(store.renew(toRenew, OWNER_A, LOCK_TTL, TIMEOUT)).isTrue();
        assertThat(store.acquire(toRenew, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("renewed, so it is not free")
                .isEqualTo(new Acquisition.InFlight("fp-a"));
    }

    @Test
    public void aCompletedRecordExpiresAfterTheRetentionAndItsKeyIsFreeAgain() {
        ScopedKey key = key("k");
        StoredResponse response = created("{}");
        store.acquire(key, OWNER_A, "fp-a", LOCK_TTL, TIMEOUT);
        store.complete(key, OWNER_A, response, RETENTION, TIMEOUT);

        clock.advance(RETENTION.minus(MARGIN));
        assertThat(store.acquire(key, OWNER_B, "fp-a", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-a", response));

        clock.advance(MARGIN.multipliedBy(2));
        assertThat(store.acquire(key, OWNER_C, "fp-c", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(store.acquire(key, OWNER_B, "fp-b", LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.InFlight("fp-c"));
    }

    @Test
    public void acceptsDurationsOfWeeks() {
        ScopedKey key = key("k");
        Duration thirtyDays = Duration.ofDays(30);
        StoredResponse response = created("{}");

        assertThat(store.acquire(key, OWNER_A, "fp-a", thirtyDays, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(store.renew(key, OWNER_A, thirtyDays, TIMEOUT)).isTrue();
        assertThat(store.complete(key, OWNER_A, response, thirtyDays, TIMEOUT)).isTrue();

        clock.advance(Duration.ofDays(29));
        assertThat(store.acquire(key, OWNER_B, "fp-a", LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed("fp-a", response));
    }

    @Test
    public void keepsKeysApartExactlyHoweverSimilarTheyAre() {
        List<ScopedKey> keys = List.of(
                new ScopedKey(scope, "k"),
                new ScopedKey(scope, "K"),
                new ScopedKey(scope, "k "),
                new ScopedKey(scope, " k"),
                new ScopedKey(scope, "\"k\""),
                new ScopedKey(scope, "k%3A"),
                new ScopedKey(scope, "x".repeat(255)),
                new ScopedKey(scope, "x".repeat(254) + "y"),
                new ScopedKey(scope + "-other", "k"),
                new ScopedKey("a:b", "c"),
                new ScopedKey("a", "b:c"),
                new ScopedKey(scope + PRECOMPOSED, "k"),
                new ScopedKey(scope + DECOMPOSED, "k"),
                new ScopedKey(scope + "💳", "k"),
                new ScopedKey(scope.substring(0, 40) + "x".repeat(215), "k"),
                new ScopedKey(scope.substring(0, 40) + "x".repeat(214) + "y", "k"));

        for (int i = 0; i < keys.size(); i++) {
            assertThat(store.acquire(keys.get(i), OWNER_A, "fp-" + i, LOCK_TTL, TIMEOUT))
                    .as("key #%d is free", i)
                    .isEqualTo(new Acquisition.Acquired());
        }
        for (int i = 0; i < keys.size(); i++) {
            assertThat(store.acquire(keys.get(i), OWNER_B, "other", LOCK_TTL, TIMEOUT))
                    .as("key #%d came back with another key's record", i)
                    .isEqualTo(new Acquisition.InFlight("fp-" + i));
        }
    }

    @Test
    public void storesEveryPayloadAsGivenAndReturnsAnEqualCopy() {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        byte[] large = new byte[1_000_000];
        new Random(42).nextBytes(large);
        List<StoredResponse> payloads = List.of(
                new StoredResponse(
                        402,
                        Map.of(
                                "location",
                                List.of("/payments/1"),
                                "content-language",
                                List.of("en", "pl"),
                                "etag",
                                List.of("\"33a64df5\""),
                                "content-type",
                                List.of("a,b", "back\\slash", "{braces}", "NULL", "", "ünïcödé 💳")),
                        "{\"message\":\"O'Reilly ü 💳\"}".getBytes(StandardCharsets.UTF_8)),
                StoredResponse.of(200, new byte[0]),
                StoredResponse.of(204, new byte[0]),
                StoredResponse.of(201, everyByte),
                StoredResponse.of(200, large),
                StoredResponse.of(304, "x".getBytes(StandardCharsets.UTF_8)));

        for (int i = 0; i < payloads.size(); i++) {
            ScopedKey key = key("payload-" + i);
            StoredResponse payload = payloads.get(i);
            store.acquire(key, OWNER_A, "fp", LOCK_TTL, TIMEOUT);
            assertThat(store.complete(key, OWNER_A, payload, RETENTION, TIMEOUT))
                    .as("complete() with payload #%d", i)
                    .isTrue();

            assertThat(store.acquire(key, OWNER_B, "fp", LOCK_TTL, TIMEOUT))
                    .as("payload #%d", i)
                    .isEqualTo(new Acquisition.Completed("fp", payload));
            assertThat(store.acquire(key, OWNER_B, "fp", LOCK_TTL, TIMEOUT))
                    .as("payload #%d, read again", i)
                    .isEqualTo(new Acquisition.Completed("fp", payload));
        }
    }

    @Test
    public void purgeDeletesOnlyExpiredRecordsUpToTheLimit() {
        for (int i = 0; i < 5; i++) {
            store.acquire(key("lock-" + i), OWNER_A, "fp", Duration.ofSeconds(10), TIMEOUT);
        }
        for (int i = 0; i < 3; i++) {
            ScopedKey key = key("done-" + i);
            store.acquire(key, OWNER_A, "fp", LOCK_TTL, TIMEOUT);
            store.complete(key, OWNER_A, created("{}"), Duration.ofSeconds(30), TIMEOUT);
        }
        for (int i = 0; i < 2; i++) {
            store.acquire(key("live-" + i), OWNER_A, "fp", Duration.ofHours(1), TIMEOUT);
        }
        clock.advance(Duration.ofMinutes(2));

        assertThat(store.purgeExpired(3, TIMEOUT)).as("the limit").isEqualTo(3);
        assertThat(store.purgeExpired(100, TIMEOUT))
                .as("the rest of the expired")
                .isEqualTo(5);
        assertThat(store.purgeExpired(100, TIMEOUT)).as("nothing left to purge").isZero();

        assertThat(store.acquire(key("live-0"), OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("a live record stays")
                .isEqualTo(new Acquisition.InFlight("fp"));
        assertThat(store.acquire(key("lock-0"), OWNER_B, "fp-b", LOCK_TTL, TIMEOUT))
                .as("a purged key is free")
                .isEqualTo(new Acquisition.Acquired());
    }

    @Test
    public void declaresWhetherItIsPersistent() {
        assertThat(store.persistent()).isEqualTo(expectedPersistent());
    }

    @Test
    public void ofManyCallersOnAFreeKeyExactlyOneWins() throws Exception {
        for (int round = 0; round < 10; round++) {
            expectOneWinner(race(key("free-" + round)), "acquire() of a free key");
        }
    }

    @Test
    public void ofManyCallersOnAnExpiredLockExactlyOneTakesItOver() throws Exception {
        ScopedKey key = key("expired-lock");
        store.acquire(key, new OwnerToken("crashed"), "fp-crashed", LOCK_TTL, TIMEOUT);
        clock.advance(LOCK_TTL.plus(MARGIN));

        expectOneWinner(race(key), "acquire() of an expired lock");
        assertThat(store.complete(key, new OwnerToken("crashed"), created("x"), RETENTION, TIMEOUT))
                .as("the crashed owner is fenced off")
                .isFalse();
    }

    @Test
    public void ofManyCallersOnAnExpiredRecordExactlyOneWins() throws Exception {
        ScopedKey key = key("expired-record");
        store.acquire(key, new OwnerToken("first"), "fp-first", LOCK_TTL, TIMEOUT);
        store.complete(key, new OwnerToken("first"), created("{}"), RETENTION, TIMEOUT);
        clock.advance(RETENTION.plus(MARGIN));

        expectOneWinner(race(key), "acquire() of an expired record");
    }

    @Test
    public void acquireRacingReleaseNeverFailsAndAtMostOneCallerWins() throws Exception {
        ScopedKey key = key("race-release");
        OwnerToken original = new OwnerToken("original");
        store.acquire(key, original, "fp-original", LOCK_TTL, TIMEOUT);

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS + 1);
        try {
            CyclicBarrier start = new CyclicBarrier(CALLERS + 1);
            Future<Boolean> released = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.release(key, original, TIMEOUT);
            });
            List<Future<Acquisition>> acquisitions = submitAcquisitions(pool, start, key);

            assertThat(released.get(30, TimeUnit.SECONDS))
                    .as("release() by the owner")
                    .isTrue();
            List<Acquisition> results = collect(acquisitions);
            List<Integer> winners = winners(results);
            assertThat(winners).as("callers that acquired the key").hasSizeLessThanOrEqualTo(1);
            for (Acquisition result : results) {
                if (result instanceof Acquisition.InFlight inFlight) {
                    List<String> possible = new ArrayList<>(List.of("fp-original"));
                    winners.forEach(winner -> possible.add("fp-" + winner));
                    assertThat(inFlight.fingerprint()).isIn(possible);
                } else {
                    assertThat(result).isEqualTo(new Acquisition.Acquired());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void acquireRacingCompleteSeesTheLockOrTheRecordNeverAFreeKey() throws Exception {
        ScopedKey key = key("race-complete");
        OwnerToken original = new OwnerToken("original");
        StoredResponse response = created("{}");
        store.acquire(key, original, "fp-original", LOCK_TTL, TIMEOUT);

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS + 1);
        try {
            CyclicBarrier start = new CyclicBarrier(CALLERS + 1);
            Future<Boolean> completed = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.complete(key, original, response, RETENTION, TIMEOUT);
            });
            List<Future<Acquisition>> acquisitions = submitAcquisitions(pool, start, key);

            assertThat(completed.get(30, TimeUnit.SECONDS))
                    .as("complete() by the owner")
                    .isTrue();
            for (Acquisition result : collect(acquisitions)) {
                assertThat(result)
                        .isIn(
                                new Acquisition.InFlight("fp-original"),
                                new Acquisition.Completed("fp-original", response));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void concurrentCallsOnDifferentKeysDoNotInterfere() throws Exception {
        int keys = CALLERS / 2;
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            CyclicBarrier start = new CyclicBarrier(CALLERS);
            List<Future<Acquisition>> results = new ArrayList<>();
            for (int i = 0; i < keys; i++) {
                ScopedKey key = key("many-" + i);
                for (String side : List.of("a", "b")) {
                    OwnerToken owner = new OwnerToken("owner-" + i + side);
                    String fingerprint = "fp-" + i + side;
                    results.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return store.acquire(key, owner, fingerprint, LOCK_TTL, TIMEOUT);
                    }));
                }
            }
            List<Acquisition> collected = collect(results);
            for (int i = 0; i < keys; i++) {
                Acquisition first = collected.get(2 * i);
                Acquisition second = collected.get(2 * i + 1);
                boolean firstWon = first instanceof Acquisition.Acquired;
                assertThat(firstWon ^ (second instanceof Acquisition.Acquired))
                        .as("exactly one caller wins the key #%d", i)
                        .isTrue();
                assertThat(firstWon ? second : first)
                        .isEqualTo(new Acquisition.InFlight("fp-" + i + (firstWon ? "a" : "b")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ScopedKey key(String name) {
        return new ScopedKey(scope, name);
    }

    private static StoredResponse created(String body) {
        return StoredResponse.of(201, body.getBytes(StandardCharsets.UTF_8));
    }

    /** {@link #CALLERS} llamadas a la vez sobre una clave; la llamada {@code i} pasa {@code owner-i} y {@code fp-i}. */
    private List<Acquisition> race(ScopedKey key) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            CyclicBarrier start = new CyclicBarrier(CALLERS);
            return collect(submitAcquisitions(pool, start, key));
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Future<Acquisition>> submitAcquisitions(ExecutorService pool, CyclicBarrier start, ScopedKey key) {
        List<Future<Acquisition>> futures = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            OwnerToken owner = new OwnerToken("owner-" + i);
            String fingerprint = "fp-" + i;
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.acquire(key, owner, fingerprint, LOCK_TTL, TIMEOUT);
            }));
        }
        return futures;
    }

    private static List<Acquisition> collect(List<Future<Acquisition>> futures) throws Exception {
        List<Acquisition> results = new ArrayList<>();
        for (Future<Acquisition> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private static List<Integer> winners(List<Acquisition> results) {
        List<Integer> winners = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) instanceof Acquisition.Acquired) {
                winners.add(i);
            }
        }
        return winners;
    }

    /** Exactamente uno ganó, y cada otro vio el lock del ganador, con la huella del ganador. */
    private static void expectOneWinner(List<Acquisition> results, String what) {
        List<Integer> winners = winners(results);
        assertThat(winners)
                .as("%s: callers that acquired the key, of %d", what, results.size())
                .hasSize(1);
        int winner = winners.get(0);
        for (int i = 0; i < results.size(); i++) {
            if (i != winner) {
                assertThat(results.get(i))
                        .as("%s: the losing caller #%d", what, i)
                        .isEqualTo(new Acquisition.InFlight("fp-" + winner));
            }
        }
    }
}
