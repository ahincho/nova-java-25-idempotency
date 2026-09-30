package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import pe.edu.nova.java.libs.idempotency.fingerprint.Sha256Fingerprinter;
import pe.edu.nova.java.libs.idempotency.memory.InMemoryIdempotencyStore;
import pe.edu.nova.java.libs.idempotency.testing.LogCapture;
import pe.edu.nova.java.libs.idempotency.testing.MutableClock;
import pe.edu.nova.java.libs.idempotency.testing.RecordingStore;

/**
 * ADR-047: ni la clave ni el cuerpo aparecen en un log. Se recorren los caminos que escriben en el log, los
 * felices y los que fallan, con una clave, un alcance y un cuerpo reconocibles, y se busca cada uno en todo lo
 * que salió: mensajes, parámetros, excepciones con su causa y su traza.
 */
class LogPrivacyTest {

    private static final String KEY = "marker-KEY-4f9d2c";
    private static final String SCOPE = "marker-SCOPE-91be7a";
    private static final String BODY = "{\"card\":\"marker-BODY-77aa30\"}";
    private static final List<String> MARKERS =
            List.of("marker-KEY", "marker-SCOPE", "marker-BODY", "4f9d2c", "91be7a");

    @Test
    void neitherTheKeyNorTheScopeNorTheBodyReachTheLog() {
        MutableClock clock = new MutableClock();
        RecordingStore store = new RecordingStore(new InMemoryIdempotencyStore(clock));
        // Un almacén ajeno que, por descuido, cita la clave en el mensaje de su excepción.
        RuntimeException leaky = new IllegalStateException("could not write " + KEY + " for " + SCOPE + ": " + BODY);
        IdempotencySettings settings = IdempotencySettings.defaults().withLockRenewal(Duration.ofMillis(10));
        String fingerprint = new Sha256Fingerprinter()
                .fingerprint(SCOPE, IdempotentRequest.of("POST", "/orders", BODY.getBytes(StandardCharsets.UTF_8)));
        StoredResponse response = StoredResponse.of(201, BODY.getBytes(StandardCharsets.UTF_8));

        try (LogCapture logs = new LogCapture();
                IdempotencyEngine engine = new IdempotencyEngine(store, settings)) {
            // El camino feliz.
            Attempt ok = ((Decision.Execute) engine.begin(KEY, SCOPE, fingerprint)).attempt();
            engine.complete(ok, response);
            engine.begin(KEY, SCOPE, fingerprint);
            engine.begin(KEY, SCOPE, "another");
            engine.begin(KEY + "\t", SCOPE, fingerprint);

            // Una renovación que falla y otra que pierde el lock.
            store.failOn("renew", leaky);
            Attempt renewing = ((Decision.Execute) engine.begin(KEY + "-2", SCOPE, fingerprint)).attempt();
            awaitFailures(store);
            store.recover();
            store.loseRenewals();
            Attempt lost = ((Decision.Execute) engine.begin(KEY + "-3", SCOPE, fingerprint)).attempt();
            awaitRenewals(store);

            // Guardar y liberar con un almacén que falla, con una excepción propia y con una ajena.
            store.failOn("complete", new IdempotencyStoreException("The store timed out on complete"));
            engine.complete(renewing, response);
            store.failOn("release", leaky);
            engine.release(lost);

            // Un intento reemplazado y una purga.
            store.failOn("complete", leaky);
            Attempt other = ((Decision.Execute) engine.begin(KEY + "-4", SCOPE, fingerprint)).attempt();
            engine.complete(other, response);
            clock.advance(Duration.ofDays(2));
            engine.purgeExpired();

            // Un almacén que falla al tomar la clave.
            store.failOn("acquire", leaky);
            assertThatThrownBy(() -> engine.begin(KEY + "-5", SCOPE, fingerprint))
                    .isSameAs(leaky);

            assertThat(logs.entries())
                    .as("the paths above did write to the log")
                    .isNotEmpty();
            for (String marker : MARKERS) {
                assertThat(logs.text())
                        .as("the log must not contain %s", marker)
                        .doesNotContain(marker);
            }
        }
    }

    @Test
    void theStringFormOfEveryValueOfTheCapabilityHidesTheKeyTheScopeAndTheBody() {
        StoredResponse response = new StoredResponse(
                201, Map.of("location", List.of("/orders/marker-KEY")), BODY.getBytes(StandardCharsets.UTF_8));
        Attempt attempt = new Attempt(new ScopedKey(SCOPE, KEY), OwnerToken.generate());
        List<Object> values = List.of(
                new ScopedKey(SCOPE, KEY),
                OwnerToken.generate(),
                response,
                attempt,
                new Decision.Execute(attempt),
                new Decision.Replay(response),
                new Decision.InProgress(Duration.ofSeconds(1)),
                new Decision.KeyReused(),
                new Acquisition.Acquired(),
                new Acquisition.InFlight("fingerprint-marker-KEY"),
                new Acquisition.Completed("fingerprint-marker-KEY", response),
                IdempotentRequest.of("POST", "/orders", Map.of("x", "marker-KEY"), SCOPE, BODY.getBytes()));

        for (Object value : values) {
            for (String marker : MARKERS) {
                assertThat(String.valueOf(value))
                        .as("%s", value.getClass().getSimpleName())
                        .doesNotContain(marker);
            }
        }
    }

    @Test
    void theMessagesOfTheExceptionsThatTheCapabilityThrowsDoNotQuoteTheKeyNorTheScope() {
        assertThatThrownBy(() -> new ScopedKey(SCOPE, KEY + "é"))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("marker"));
        assertThatThrownBy(() -> new ScopedKey(" ", KEY))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("marker"));
        assertThatThrownBy(() -> new IdempotencyEngine(new InMemoryIdempotencyStore()).begin(KEY, "", "fp"))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("marker"));
    }

    private static void awaitFailures(RecordingStore store) {
        await(() -> store.renews() >= 3);
    }

    private static void awaitRenewals(RecordingStore store) {
        int before = store.renews();
        await(() -> store.renews() > before);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }
}
