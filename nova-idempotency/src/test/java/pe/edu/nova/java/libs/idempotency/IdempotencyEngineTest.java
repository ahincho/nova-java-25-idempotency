package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.edu.nova.java.libs.idempotency.memory.InMemoryIdempotencyStore;
import pe.edu.nova.java.libs.idempotency.testing.LogCapture;
import pe.edu.nova.java.libs.idempotency.testing.MutableClock;
import pe.edu.nova.java.libs.idempotency.testing.RecordingStore;

class IdempotencyEngineTest {

    private static final String SCOPE = "customer-42";
    private static final String KEY = "order-1";
    private static final String FINGERPRINT = "fp-1";

    private MutableClock clock;
    private InMemoryIdempotencyStore memory;
    private RecordingStore store;
    private IdempotencyEngine engine;

    @BeforeEach
    void openEngine() {
        clock = new MutableClock();
        memory = new InMemoryIdempotencyStore(clock);
        store = new RecordingStore(memory);
        engine = new IdempotencyEngine(store);
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void aFreeKeyIsExecuted() {
        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
    }

    @Test
    void eachAttemptHasItsOwnOwnerToken() {
        Attempt first = attempt(engine.begin("k1", SCOPE, FINGERPRINT));
        Attempt second = attempt(engine.begin("k2", SCOPE, FINGERPRINT));
        clock.advance(Duration.ofMinutes(2));
        Attempt takeover = attempt(engine.begin("k1", SCOPE, FINGERPRINT));

        assertThat(List.of(first.owner(), second.owner(), takeover.owner())).doesNotHaveDuplicates();
    }

    @Test
    void theSameKeyWhileTheOperationRunsIsInProgress() {
        engine.begin(KEY, SCOPE, FINGERPRINT);

        Decision decision = engine.begin(KEY, SCOPE, FINGERPRINT);

        assertThat(decision).isInstanceOf(Decision.InProgress.class);
        assertThat(((Decision.InProgress) decision).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void theRetryAfterFollowsTheSettingsAndRoundsUp() {
        try (IdempotencyEngine slow =
                new IdempotencyEngine(store, IdempotencySettings.defaults().withRetryAfter(Duration.ofMillis(2_500)))) {
            slow.begin(KEY, SCOPE, FINGERPRINT);

            assertThat(slow.begin(KEY, SCOPE, FINGERPRINT))
                    .isEqualTo(new Decision.InProgress(Duration.ofMillis(2_500)));
            assertThat(new Decision.InProgress(Duration.ofMillis(2_500)).retryAfterSeconds())
                    .isEqualTo(3);
            assertThat(new Decision.InProgress(Duration.ofMillis(1)).retryAfterSeconds())
                    .isEqualTo(1);
        }
    }

    @Test
    void theSameKeyWithAnotherFingerprintIsReusedWhileRunningAndAfterwards() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));

        assertThat(engine.begin(KEY, SCOPE, "another")).as("while it runs").isEqualTo(new Decision.KeyReused());

        engine.complete(attempt, created("{}"));
        assertThat(engine.begin(KEY, SCOPE, "another")).as("once answered").isEqualTo(new Decision.KeyReused());
    }

    @Test
    void theSameKeyOnceAnsweredRepeatsTheStoredResponse() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        StoredResponse response = new StoredResponse(
                201, Map.of("location", List.of("/orders/1")), "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(engine.complete(attempt, response)).isEqualTo(Completion.STORED);

        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(response));
    }

    @Test
    void aClientErrorIsStoredAndRepeated() {
        for (int status : new int[] {400, 404, 409, 422}) {
            String key = "key-" + status;
            Attempt attempt = attempt(engine.begin(key, SCOPE, FINGERPRINT));
            StoredResponse response = StoredResponse.of(status, new byte[] {1});

            assertThat(engine.complete(attempt, response))
                    .as("status %d", status)
                    .isEqualTo(Completion.STORED);
            assertThat(engine.begin(key, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(response));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 599})
    void aServerErrorIsNeverStoredAndFreesTheKey(int status) {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));

        assertThat(engine.complete(attempt, StoredResponse.of(status, new byte[0])))
                .isEqualTo(Completion.RELEASED);

        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT))
                .as("the client can retry")
                .isInstanceOf(Decision.Execute.class);
    }

    @Test
    void releaseFreesTheKeyWithoutStoringAnything() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));

        assertThat(engine.release(attempt)).isEqualTo(Completion.RELEASED);

        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
    }

    @Test
    void onlyTheReplayableHeadersAreStored() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        Map<String, List<String>> headers = Map.ofEntries(
                Map.entry("Location", List.of("/orders/1")),
                Map.entry("Content-Type", List.of("application/json")),
                Map.entry("Content-Language", List.of("es")),
                Map.entry("Content-Location", List.of("/orders/1.json")),
                Map.entry("ETag", List.of("\"v1\"")),
                Map.entry("Last-Modified", List.of("Wed, 30 Sep 2026 12:00:00 GMT")),
                Map.entry("Set-Cookie", List.of("session=abc")),
                Map.entry("Cookie", List.of("session=abc")),
                Map.entry("Authorization", List.of("Bearer secret")),
                Map.entry("Access-Control-Allow-Origin", List.of("*")),
                Map.entry("Content-Length", List.of("8")),
                Map.entry("Date", List.of("Wed, 30 Sep 2026 12:00:00 GMT")),
                Map.entry("X-Request-Id", List.of("r-1")));

        engine.complete(attempt, new StoredResponse(200, headers, new byte[] {1}));

        Decision.Replay replay = (Decision.Replay) engine.begin(KEY, SCOPE, FINGERPRINT);
        assertThat(replay.response().headers().keySet())
                .containsExactlyInAnyOrder(
                        "location", "content-type", "content-language", "content-location", "etag", "last-modified");
    }

    @Test
    void theHeadersOfTheSettingsAreStoredToo() {
        IdempotencySettings settings = IdempotencySettings.defaults().withReplayHeaders(List.of("X-Order-Version"));
        try (IdempotencyEngine custom = new IdempotencyEngine(store, settings)) {
            Attempt attempt = attempt(custom.begin(KEY, SCOPE, FINGERPRINT));
            custom.complete(
                    attempt,
                    new StoredResponse(
                            200, Map.of("x-order-version", List.of("3"), "x-request-id", List.of("r-1")), new byte[0]));

            Decision.Replay replay = (Decision.Replay) custom.begin(KEY, SCOPE, FINGERPRINT);
            assertThat(replay.response().headers()).containsOnlyKeys("x-order-version");
        }
    }

    @Test
    void aReplayNeverRepeatsCookiesEvenIfTheStoreHoldsThem() {
        // Un registro escrito por otra versión, u otro adaptador, con headers que esta capacidad nunca guarda.
        OwnerToken owner = OwnerToken.generate();
        ScopedKey key = new ScopedKey(SCOPE, KEY);
        memory.acquire(key, owner, FINGERPRINT, Duration.ofSeconds(60), Duration.ofSeconds(5));
        memory.complete(
                key,
                owner,
                new StoredResponse(
                        200,
                        Map.of(
                                "set-cookie", List.of("session=abc"),
                                "authorization", List.of("Bearer secret"),
                                "content-type", List.of("application/json")),
                        new byte[0]),
                Duration.ofHours(24),
                Duration.ofSeconds(5));

        Decision.Replay replay = (Decision.Replay) engine.begin(KEY, SCOPE, FINGERPRINT);

        assertThat(replay.response().headers()).containsOnlyKeys("content-type");
    }

    @Test
    void aMissingKeyIsReported() {
        assertThat(engine.begin(null, SCOPE, FINGERPRINT)).isEqualTo(new Decision.KeyMissing());
        assertThat(engine.begin("", SCOPE, FINGERPRINT)).isEqualTo(new Decision.KeyMissing());
        assertThat(store.acquires()).as("nothing reached the store").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\tb", "a\nb", "clave-é", "\u007f", "😀", "a\u0000b", "tab\t"})
    void aKeyWithCharactersThatAreNotPrintableAsciiIsInvalid(String key) {
        assertThat(engine.begin(key, SCOPE, FINGERPRINT)).isEqualTo(new Decision.KeyInvalid());
    }

    @Test
    void aKeyLongerThan255CharactersIsInvalidAndOneOf255Works() {
        assertThat(engine.begin("k".repeat(256), SCOPE, FINGERPRINT)).isEqualTo(new Decision.KeyInvalid());
        assertThat(engine.begin("k".repeat(255), SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        assertThat(engine.begin("k", SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        assertThat(store.acquires()).isEqualTo(2);
    }

    @Test
    void theKeyIsTakenAsItArrivesSoQuotesAndSpacesMakeAnotherKey() {
        for (String key : List.of("abc", "\"abc\"", " abc", "abc ", "ABC", "'abc'")) {
            assertThat(engine.begin(key, SCOPE, FINGERPRINT)).as("key %s", key).isInstanceOf(Decision.Execute.class);
        }
    }

    @Test
    void theScopeIsPartOfTheKey() {
        assertThat(engine.begin(KEY, "alice", FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        assertThat(engine.begin(KEY, "bob", FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        assertThat(engine.begin(KEY, "alice", FINGERPRINT)).isInstanceOf(Decision.InProgress.class);
    }

    @Test
    void aClientNeverReceivesTheResponseOfAnotherClient() {
        Attempt alice = attempt(engine.begin(KEY, "alice", FINGERPRINT));
        engine.complete(alice, created("{\"order\":\"alice's\"}"));

        assertThat(engine.begin(KEY, "bob", FINGERPRINT))
                .as("bob sends alice's key with the same content")
                .isInstanceOf(Decision.Execute.class);
    }

    @Test
    void anEmptyScopeIsRejectedBecauseTheKeyIsNeverGlobal() {
        for (String scope : new String[] {"", "   ", "\t"}) {
            assertThatThrownBy(() -> engine.begin(KEY, scope, FINGERPRINT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(KEY);
        }
        assertThatThrownBy(() -> engine.begin(KEY, null, FINGERPRINT)).isInstanceOf(NullPointerException.class);
        assertThat(store.acquires()).isZero();
    }

    @Test
    void aScopeThatCannotBeStoredIsRejected() {
        assertThatThrownBy(() -> engine.begin(KEY, "s".repeat(256), FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.begin(KEY, "a\u0000b", FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBadFingerprintIsRejected() {
        assertThatThrownBy(() -> engine.begin(KEY, SCOPE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.begin(KEY, SCOPE, "f".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.begin(KEY, SCOPE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anAttemptIsClosedOnlyOnce() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        StoredResponse response = created("{}");

        assertThat(engine.complete(attempt, response)).isEqualTo(Completion.STORED);
        assertThat(engine.release(attempt))
                .as("a second close returns the first outcome")
                .isEqualTo(Completion.STORED);
        assertThat(engine.complete(attempt, created("other"))).isEqualTo(Completion.STORED);

        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(response));
        assertThat(store.completes()).isEqualTo(1);
        assertThat(store.releases()).isZero();
    }

    @Test
    void theResponseIsRepeatedForTheRetentionAndThenTheKeyIsFree() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        StoredResponse response = created("{}");
        engine.complete(attempt, response);

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT))
                .as("just before 24 hours")
                .isEqualTo(new Decision.Replay(response));

        clock.advance(Duration.ofMinutes(2));
        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).as("after 24 hours").isInstanceOf(Decision.Execute.class);
    }

    @Test
    void theRetentionFollowsTheSettings() {
        try (IdempotencyEngine briefRetention =
                new IdempotencyEngine(store, IdempotencySettings.defaults().withRetention(Duration.ofMinutes(10)))) {
            Attempt attempt = attempt(briefRetention.begin(KEY, SCOPE, FINGERPRINT));
            briefRetention.complete(attempt, created("{}"));

            clock.advance(Duration.ofMinutes(9));
            assertThat(briefRetention.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Replay.class);
            clock.advance(Duration.ofMinutes(2));
            assertThat(briefRetention.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        }
    }

    @Test
    void aLockWithoutRenewalExpiresAndTheNextAttemptTakesTheKey() {
        try (IdempotencyEngine noHeartbeat =
                new IdempotencyEngine(store, IdempotencySettings.defaults().withLockRenewal(Duration.ZERO))) {
            noHeartbeat.begin(KEY, SCOPE, FINGERPRINT);

            clock.advance(Duration.ofSeconds(59));
            assertThat(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT))
                    .as("before 60 s")
                    .isInstanceOf(Decision.InProgress.class);

            clock.advance(Duration.ofSeconds(2));
            assertThat(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT))
                    .as("after 60 s")
                    .isInstanceOf(Decision.Execute.class);
        }
        assertThat(store.renews()).isZero();
    }

    @Test
    void theLockIsRenewedWhileTheOperationRuns() {
        // El latido corre en tiempo real y el almacén mide con el reloj de la prueba: se le da una renovación
        // corta, y cada renovación mueve el vencimiento del lock 60 s por delante del reloj de la prueba.
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(20));
        try (IdempotencyEngine renewing = new IdempotencyEngine(store, settings)) {
            renewing.begin(KEY, SCOPE, FINGERPRINT);

            int renewalsBefore = store.renews();
            clock.advance(Duration.ofSeconds(59));
            awaitTrue("two renewals after the clock moved", () -> store.renews() >= renewalsBefore + 2);
            clock.advance(Duration.ofSeconds(59));

            assertThat(renewing.begin(KEY, SCOPE, FINGERPRINT))
                    .as("118 s after the start, but renewed at 59 s")
                    .isInstanceOf(Decision.InProgress.class);
        }
    }

    @Test
    void theHeartbeatKeepsALongOperationAliveInRealTime() throws InterruptedException {
        InMemoryIdempotencyStore realTime = new InMemoryIdempotencyStore();
        IdempotencySettings settings = IdempotencySettings.defaults()
                .withLockTtl(Duration.ofMillis(500))
                .withLockRenewal(Duration.ofMillis(50));
        try (IdempotencyEngine renewing = new IdempotencyEngine(realTime, settings)) {
            Attempt attempt = attempt(renewing.begin(KEY, SCOPE, FINGERPRINT));

            Thread.sleep(1_500);

            assertThat(renewing.begin(KEY, SCOPE, FINGERPRINT))
                    .as("three times the lock ttl later")
                    .isInstanceOf(Decision.InProgress.class);
            assertThat(renewing.complete(attempt, created("{}"))).isEqualTo(Completion.STORED);
        }
    }

    @Test
    void withoutTheHeartbeatALongOperationLosesItsKey() throws InterruptedException {
        InMemoryIdempotencyStore realTime = new InMemoryIdempotencyStore();
        IdempotencySettings settings = IdempotencySettings.defaults()
                .withLockTtl(Duration.ofMillis(200))
                .withLockRenewal(Duration.ZERO);
        try (IdempotencyEngine engineWithoutHeartbeat = new IdempotencyEngine(realTime, settings)) {
            Attempt slow = attempt(engineWithoutHeartbeat.begin(KEY, SCOPE, FINGERPRINT));

            Thread.sleep(500);

            assertThat(engineWithoutHeartbeat.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
            assertThat(engineWithoutHeartbeat.complete(slow, created("{}")))
                    .as("the first attempt was replaced")
                    .isEqualTo(Completion.LOST);
        }
    }

    @Test
    void aStaleAttemptIsFencedOffAndNeverOverwritesTheOneThatReplacedIt() {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ZERO);
        try (IdempotencyEngine noHeartbeat = new IdempotencyEngine(store, settings)) {
            Attempt stale = attempt(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT));
            clock.advance(Duration.ofSeconds(61));
            Attempt current = attempt(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT));
            StoredResponse fresh = created("fresh");

            assertThat(noHeartbeat.complete(stale, created("stale"))).isEqualTo(Completion.LOST);
            assertThat(noHeartbeat.release(stale)).as("already closed").isEqualTo(Completion.LOST);
            assertThat(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.InProgress.class);
            assertThat(noHeartbeat.complete(current, fresh)).isEqualTo(Completion.STORED);

            assertThat(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(fresh));
        }
    }

    @Test
    void aSlowAttemptThatNobodyReplacedStillStoresItsResponse() {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ZERO);
        try (IdempotencyEngine noHeartbeat = new IdempotencyEngine(store, settings)) {
            Attempt slow = attempt(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT));
            clock.advance(Duration.ofMinutes(5));
            StoredResponse response = created("late");

            assertThat(noHeartbeat.complete(slow, response)).isEqualTo(Completion.STORED);

            assertThat(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT))
                    .as("the retry gets the response instead of running the operation again")
                    .isEqualTo(new Decision.Replay(response));
        }
    }

    @Test
    void theHeartbeatStopsWhenTheAttemptIsCompleted() throws InterruptedException {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(20));
        try (IdempotencyEngine renewing = new IdempotencyEngine(store, settings)) {
            Attempt attempt = attempt(renewing.begin(KEY, SCOPE, FINGERPRINT));
            awaitTrue("the first renewal", () -> store.renews() >= 1);

            renewing.complete(attempt, created("{}"));
            Thread.sleep(150);
            int afterCompleting = store.renews();
            Thread.sleep(300);

            assertThat(store.renews()).as("no renewals once completed").isEqualTo(afterCompleting);
        }
    }

    @Test
    void theHeartbeatStopsWhenTheAttemptIsReleased() throws InterruptedException {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(20));
        try (IdempotencyEngine renewing = new IdempotencyEngine(store, settings)) {
            Attempt attempt = attempt(renewing.begin(KEY, SCOPE, FINGERPRINT));
            awaitTrue("the first renewal", () -> store.renews() >= 1);

            renewing.release(attempt);
            Thread.sleep(150);
            int afterReleasing = store.renews();
            Thread.sleep(300);

            assertThat(store.renews()).isEqualTo(afterReleasing);
        }
    }

    @Test
    void closingTheEngineStopsTheHeartbeats() throws InterruptedException {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(20));
        IdempotencyEngine renewing = new IdempotencyEngine(store, settings);
        renewing.begin(KEY, SCOPE, FINGERPRINT);
        awaitTrue("the first renewal", () -> store.renews() >= 1);

        renewing.close();
        renewing.close();
        Thread.sleep(150);
        int afterClosing = store.renews();
        Thread.sleep(300);

        assertThat(store.renews()).isEqualTo(afterClosing);
    }

    @Test
    void aLostLockIsLoggedOnceAndTheHeartbeatStops() throws InterruptedException {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(20));
        store.loseRenewals();
        try (LogCapture logs = new LogCapture();
                IdempotencyEngine renewing = new IdempotencyEngine(store, settings)) {
            Attempt attempt = attempt(renewing.begin(KEY, SCOPE, FINGERPRINT));

            awaitTrue(
                    "the warning",
                    () -> !logs.entriesContaining("lost its lock").isEmpty());
            Thread.sleep(200);

            assertThat(logs.entriesContaining("lost its lock")).hasSize(1);
            assertThat(store.renews())
                    .as("the heartbeat stopped after the first refusal")
                    .isEqualTo(1);
            renewing.complete(attempt, created("{}"));
        }
    }

    @Test
    void aLostLockIsNotLoggedTwiceWhenTheStaleAttemptCloses() {
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ZERO);
        try (LogCapture logs = new LogCapture();
                IdempotencyEngine noHeartbeat = new IdempotencyEngine(store, settings)) {
            Attempt stale = attempt(noHeartbeat.begin(KEY, SCOPE, FINGERPRINT));
            clock.advance(Duration.ofSeconds(61));
            noHeartbeat.begin(KEY, SCOPE, FINGERPRINT);

            assertThat(noHeartbeat.complete(stale, created("stale"))).isEqualTo(Completion.LOST);

            assertThat(logs.entriesContaining("no longer the owner")).hasSize(1);
        }
    }

    @Test
    void aFailingStoreWhenCompletingDoesNotFailTheRequest() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        store.failOn("complete", new IdempotencyStoreException("The store timed out on complete"));

        try (LogCapture logs = new LogCapture()) {
            assertThat(engine.complete(attempt, created("{}"))).isEqualTo(Completion.FAILED);
            assertThat(logs.entriesContaining("could not store the response")).hasSize(1);
        }
        assertThat(engine.begin(KEY, SCOPE, FINGERPRINT))
                .as("the key stays taken until the lock expires")
                .isInstanceOf(Decision.InProgress.class);
    }

    @Test
    void aFailingStoreWhenReleasingDoesNotFailTheRequest() {
        Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
        store.failOn("release", new IdempotencyStoreException("The store timed out on release"));

        assertThat(engine.release(attempt)).isEqualTo(Completion.FAILED);
        assertThat(engine.complete(attempt, StoredResponse.of(503, new byte[0])))
                .as("closed already")
                .isEqualTo(Completion.FAILED);
    }

    @Test
    void aFailingStoreWhenBeginningFailsTheCallAndTriesToReleaseTheLock() {
        IdempotencyStoreException failure = new IdempotencyStoreException("The store is down");
        store.failOn("acquire", failure);

        assertThatThrownBy(() -> engine.begin(KEY, SCOPE, FINGERPRINT)).isSameAs(failure);

        awaitTrue("the release of a lock the store may have taken", () -> store.releases() >= 1);
    }

    @Test
    void purgeExpiredDeletesByBatchesUntilNoneRemain() {
        IdempotencySettings settings = IdempotencySettings.defaults().withPurgeBatchSize(3);
        try (IdempotencyEngine batching = new IdempotencyEngine(store, settings)) {
            for (int i = 0; i < 8; i++) {
                batching.begin("k" + i, SCOPE, FINGERPRINT);
            }
            clock.advance(Duration.ofMinutes(5));

            assertThat(batching.purgeExpired()).isEqualTo(8);
            assertThat(store.purges()).as("batches of 3, 3 and 2").isEqualTo(3);
            assertThat(batching.purgeExpired()).isZero();
        }
    }

    @Test
    void purgeExpiredAsksOnceMoreWhenTheLastBatchWasFull() {
        IdempotencySettings settings = IdempotencySettings.defaults().withPurgeBatchSize(3);
        try (IdempotencyEngine batching = new IdempotencyEngine(store, settings)) {
            for (int i = 0; i < 6; i++) {
                batching.begin("k" + i, SCOPE, FINGERPRINT);
            }
            clock.advance(Duration.ofMinutes(5));

            assertThat(batching.purgeExpired()).isEqualTo(6);
            assertThat(store.purges()).as("3, 3 and an empty batch").isEqualTo(3);
        }
    }

    @Test
    void ofManyConcurrentRequestsWithTheSameKeyOnlyOneExecutes() throws Exception {
        int callers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CyclicBarrier start = new CyclicBarrier(callers);
            List<Future<Decision>> decisions = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                decisions.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return engine.begin(KEY, SCOPE, FINGERPRINT);
                }));
            }

            int executions = 0;
            int inProgress = 0;
            for (Future<Decision> decision : decisions) {
                switch (decision.get(30, TimeUnit.SECONDS)) {
                    case Decision.Execute execute -> executions++;
                    case Decision.InProgress waiting -> inProgress++;
                    default -> throw new AssertionError("Unexpected decision");
                }
            }
            assertThat(executions).isEqualTo(1);
            assertThat(inProgress).isEqualTo(callers - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theEngineNeedsAStoreAndSettings() {
        assertThatThrownBy(() -> new IdempotencyEngine(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyEngine(store, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theDefaultsOfTheAdrAreTheDefaultsOfTheEngine() {
        assertThat(IdempotencySettings.defaults().retention()).isEqualTo(Duration.ofHours(24));
        assertThat(IdempotencySettings.defaults().lockTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(IdempotencySettings.defaults().lockRenewal()).isEqualTo(Duration.ofSeconds(20));
    }

    private static Attempt attempt(Decision decision) {
        assertThat(decision).isInstanceOf(Decision.Execute.class);
        return ((Decision.Execute) decision).attempt();
    }

    private static StoredResponse created(String body) {
        return StoredResponse.of(201, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }
}
