package pe.edu.nova.java.starters.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;
import pe.edu.nova.java.libs.idempotency.memory.InMemoryIdempotencyStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Las piezas del starter por separado. */
class StarterPiecesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theEnvelopeCarriesTheCodeTheMessageAndRetryAfterRoundedUp() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new NovaEnvelopeErrorResponder(JSON).respond(response, IdempotencyError.keyInUse(Duration.ofMillis(1500)));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("status").asInt()).isEqualTo(409);
        assertThat(body.path("errors").path(0).path("code").asString()).isEqualTo(IdempotencyError.KEY_IN_USE);
    }

    @Test
    void anErrorWithoutRetryAfterHasNoHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new NovaEnvelopeErrorResponder(JSON).respond(response, IdempotencyError.keyReused());

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void theMessagesAreInSpanishAndTheCodesAreThoseOfAdr047() {
        assertThat(IdempotencyError.keyRequired().message()).isEqualTo("Falta el header Idempotency-Key");
        assertThat(IdempotencyError.keyInvalid().code()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(IdempotencyError.unauthenticated().status()).isEqualTo(401);
        assertThat(IdempotencyError.storeUnavailable().status()).isEqualTo(503);
        assertThat(IdempotencyError.storeUnavailable().message())
                .isEqualTo("El servicio no está disponible en este momento");
    }

    @Test
    void anErrorNeedsAnErrorStatus() {
        assertThatThrownBy(() -> new IdempotencyError(200, "OK", "ok", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyError(600, "X", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCachedBodyIsReadAgainAsAStreamAndAsAReader() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/orders");
        original.setContent("{\"sku\":\"ñandú\"}".getBytes(StandardCharsets.UTF_8));
        original.setCharacterEncoding("UTF-8");

        CachedBodyRequest cached = new CachedBodyRequest(original);

        assertThat(cached.getInputStream().readAllBytes()).isEqualTo(cached.body());
        assertThat(cached.getInputStream().isReady()).isTrue();
        try (BufferedReader reader = cached.getReader()) {
            assertThat(reader.readLine()).isEqualTo("{\"sku\":\"ñandú\"}");
        }
        assertThat(cached.getContentLength()).isEqualTo(cached.body().length);
        assertThat(cached.getContentLengthLong()).isEqualTo(cached.body().length);
        assertThatThrownBy(() -> cached.getInputStream().setReadListener(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theRequestViewCarriesTheQueryAndThePrincipalButNotTheBodyInItsText() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/orders");
        original.setQueryString("channel=web");
        original.setUserPrincipal(() -> "customer-a");
        original.addHeader("X-Customer-Id", "customer-a");
        original.setContent("secret-body".getBytes(StandardCharsets.UTF_8));

        ServletIdempotentRequest view = new ServletIdempotentRequest(new CachedBodyRequest(original));

        assertThat(view.method()).isEqualTo("POST");
        assertThat(view.path()).isEqualTo("/orders?channel=web");
        assertThat(view.principal()).hasValue("customer-a");
        assertThat(view.header("x-customer-id")).hasValue("customer-a");
        assertThat(view.body()).asString(StandardCharsets.UTF_8).isEqualTo("secret-body");
        assertThat(view.toString()).doesNotContain("secret-body");
    }

    @Test
    void theRequestViewWithoutAPrincipalHasNone() throws Exception {
        ServletIdempotentRequest view =
                new ServletIdempotentRequest(new CachedBodyRequest(new MockHttpServletRequest("POST", "/orders")));

        assertThat(view.principal()).isEmpty();
        assertThat(view.path()).isEqualTo("/orders");
    }

    @Test
    void aPurgeThatFailsIsLoggedAndTheNextRoundTriesAgain() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryIdempotencyStore memory = new InMemoryIdempotencyStore();
        IdempotencyStore failing = (IdempotencyStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IdempotencyStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("purgeExpired")) {
                        calls.incrementAndGet();
                        throw new IdempotencyStoreException("The store is down");
                    }
                    return method.invoke(memory, args);
                });
        IdempotencyPurger purger = new IdempotencyPurger(new IdempotencyEngine(failing), Duration.ofHours(1));

        purger.purge();
        purger.purge();

        assertThat(calls).hasValue(2);
    }

    @Test
    void thePurgerStartsAndStopsAndCanBeTurnedOff() {
        IdempotencyEngine engine = new IdempotencyEngine(new InMemoryIdempotencyStore());
        IdempotencyPurger scheduled = new IdempotencyPurger(engine, Duration.ofMillis(10));
        IdempotencyPurger off = new IdempotencyPurger(engine, Duration.ZERO);

        scheduled.start();
        off.start();
        assertThat(scheduled.isRunning()).isTrue();
        assertThat(off.isRunning()).isTrue();

        scheduled.stop();
        off.stop();
        assertThat(scheduled.isRunning()).isFalse();
        assertThat(off.isRunning()).isFalse();
    }
}
