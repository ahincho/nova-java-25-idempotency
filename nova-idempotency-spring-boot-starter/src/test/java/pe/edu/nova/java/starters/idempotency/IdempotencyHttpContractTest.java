package pe.edu.nova.java.starters.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * El contrato por HTTP de ADR-047, contra un Tomcat real y el almacén en memoria, con el alcance en el header
 * {@code X-Customer-Id}.
 */
@SpringBootTest(
        classes = IdempotencyHttpContractTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"nova.idempotency.store=memory", "nova.idempotency.scope-header=X-Customer-Id"})
class IdempotencyHttpContractTest {

    private static final String ORDER = "{\"sku\":\"SKU-1\",\"quantity\":2}";

    @Value("${local.server.port}")
    private int port;

    private Http http;

    @BeforeEach
    void setUp() {
        OrdersController.reset();
        http = new Http(port);
    }

    @Test
    void theFirstRequestRunsTheOperation() {
        HttpResponse<String> response = order("key-first").send();

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).hasValue("/orders/1");
        assertThat(response.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .isEmpty();
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void aRetryGetsTheStoredResponseWithoutRunningTheOperationAgain() {
        HttpResponse<String> first = order("key-retry").send();
        HttpResponse<String> retry = order("key-retry").send();

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Location")).hasValue("/orders/1");
        assertThat(retry.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void theReplayNeverRepeatsCookiesNorHeadersOutsideTheAllowedOnes() {
        HttpResponse<String> first = order("key-cookie").send();
        HttpResponse<String> retry = order("key-cookie").send();

        assertThat(first.headers().firstValue("Set-Cookie")).isPresent();
        assertThat(first.headers().firstValue("X-Order-Trace")).isPresent();
        assertThat(retry.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(retry.headers().firstValue("X-Order-Trace")).isEmpty();
    }

    @Test
    void theSameJsonWrittenAnotherWayIsTheSameRequest() {
        order("key-json").send();
        HttpResponse<String> retry = http.post("/orders")
                .key("key-json")
                .customer("customer-a")
                .body("{ \"quantity\" : 2 , \"sku\" : \"SKU-1\" }")
                .send();

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
    }

    @Test
    void theSameKeyWithOtherContentIs422() {
        order("key-reused").send();
        HttpResponse<String> reused = http.post("/orders")
                .key("key-reused")
                .customer("customer-a")
                .body("{\"sku\":\"SKU-2\",\"quantity\":2}")
                .send();

        assertError(reused, 422, IdempotencyError.KEY_REUSED);
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void theSameKeyOfAnotherCustomerIsAnotherKey() {
        HttpResponse<String> mine = order("key-shared").send();
        HttpResponse<String> theirs = http.post("/orders")
                .key("key-shared")
                .customer("customer-b")
                .body(ORDER)
                .send();

        assertThat(theirs.statusCode()).isEqualTo(201);
        assertThat(theirs.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .isEmpty();
        assertThat(theirs.body()).isNotEqualTo(mine.body());
        assertThat(OrdersController.EXECUTIONS).hasValue(2);
    }

    @Test
    void aMissingKeyIs400() {
        HttpResponse<String> response =
                http.post("/orders").customer("customer-a").body(ORDER).send();

        assertError(response, 400, IdempotencyError.KEY_REQUIRED);
        assertThat(OrdersController.EXECUTIONS).hasValue(0);
    }

    @Test
    void aKeyLongerThan255CharactersIs400() {
        HttpResponse<String> response = order("k".repeat(256)).send();

        assertError(response, 400, IdempotencyError.KEY_INVALID);
        assertThat(OrdersController.EXECUTIONS).hasValue(0);
    }

    @Test
    void aRequestWithoutTheScopeHeaderIs400AndNamesTheHeader() {
        HttpResponse<String> response =
                http.post("/orders").key("key-scope").body(ORDER).send();

        assertError(response, 400, "BAD_REQUEST");
        assertThat(Http.json(response).path("errors").path(0).path("message").asString())
                .isEqualTo("Falta el header X-Customer-Id");
        assertThat(OrdersController.EXECUTIONS).hasValue(0);
    }

    @Test
    void aKeyStillRunningIs409WithRetryAfter() throws Exception {
        CompletableFuture<HttpResponse<String>> first = http.post("/orders/slow")
                .key("key-slow")
                .customer("customer-a")
                .body(ORDER)
                .sendAsync();
        assertThat(OrdersController.entered.await(10, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> duplicate = http.post("/orders/slow")
                .key("key-slow")
                .customer("customer-a")
                .body(ORDER)
                .send();
        OrdersController.proceed.countDown();

        assertError(duplicate, 409, IdempotencyError.KEY_IN_USE);
        assertThat(duplicate.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void aServerErrorIsNotStoredSoTheRetryRunsTheOperationAgain() {
        HttpResponse<String> first = http.post("/orders/fail")
                .key("key-fail")
                .customer("customer-a")
                .body(ORDER)
                .send();
        HttpResponse<String> retry = http.post("/orders/fail")
                .key("key-fail")
                .customer("customer-a")
                .body(ORDER)
                .send();

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(retry.statusCode()).isEqualTo(503);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .isEmpty();
        assertThat(OrdersController.EXECUTIONS).hasValue(2);
    }

    @Test
    void anOperationThatThrowsReleasesTheKey() {
        HttpResponse<String> first = http.post("/orders/throw")
                .key("key-throw")
                .customer("customer-a")
                .body(ORDER)
                .send();
        HttpResponse<String> retry = http.post("/orders/throw")
                .key("key-throw")
                .customer("customer-a")
                .body(ORDER)
                .send();

        assertThat(first.statusCode()).isEqualTo(500);
        assertThat(retry.statusCode()).isEqualTo(500);
        assertThat(OrdersController.EXECUTIONS).hasValue(2);
    }

    @Test
    void aClientErrorIsStoredLikeAnyOtherResponse() {
        http.post("/orders/rejected")
                .key("key-rejected")
                .customer("customer-a")
                .body(ORDER)
                .send();
        HttpResponse<String> retry = http.post("/orders/rejected")
                .key("key-rejected")
                .customer("customer-a")
                .body(ORDER)
                .send();

        assertThat(retry.statusCode()).isEqualTo(422);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void anErrorThatTheContainerWritesIsNotStored() {
        HttpResponse<String> first = http.post("/orders/send-error")
                .key("key-error")
                .customer("customer-a")
                .body(ORDER)
                .send();
        HttpResponse<String> retry = http.post("/orders/send-error")
                .key("key-error")
                .customer("customer-a")
                .body(ORDER)
                .send();

        assertThat(first.statusCode()).isEqualTo(409);
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .isEmpty();
        assertThat(OrdersController.EXECUTIONS).hasValue(2);
    }

    @Test
    void aValidationErrorOfSpringMvcReleasesTheKey() {
        HttpResponse<String> first = http.post("/orders/needs-param")
                .key("key-param")
                .customer("customer-a")
                .send();
        HttpResponse<String> fixed = http.post("/orders/needs-param?channel=web")
                .key("key-param")
                .customer("customer-a")
                .send();

        assertThat(first.statusCode()).isEqualTo(400);
        assertThat(fixed.statusCode()).isEqualTo(200);
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    @Test
    void theQueryIsPartOfTheRequest() {
        http.post("/orders/needs-param?channel=web")
                .key("key-query")
                .customer("customer-a")
                .send();
        HttpResponse<String> other = http.post("/orders/needs-param?channel=app")
                .key("key-query")
                .customer("customer-a")
                .send();

        assertError(other, 422, IdempotencyError.KEY_REUSED);
    }

    @Test
    void anOperationWithoutTheAnnotationIsLeftAlone() {
        HttpResponse<String> first = http.post("/orders/plain").body(ORDER).send();
        HttpResponse<String> second =
                http.post("/orders/plain").key("key-plain").body(ORDER).send();

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(OrdersController.EXECUTIONS).hasValue(2);
    }

    @Test
    void aReadIsLeftAlone() {
        assertThat(http.get("/orders").statusCode()).isEqualTo(200);
    }

    @Test
    void anUnknownRouteIsLeftToSpringMvc() {
        assertThat(http.post("/nowhere")
                        .key("key-nowhere")
                        .customer("customer-a")
                        .send()
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void theAnnotationOnTheClassCoversEveryOperation() {
        HttpResponse<String> missing = http.post("/payments")
                .customer("customer-a")
                .body("{\"amount\":10}")
                .send();
        http.post("/payments")
                .key("key-pay")
                .customer("customer-a")
                .body("{\"amount\":10}")
                .send();
        HttpResponse<String> retry = http.post("/payments")
                .key("key-pay")
                .customer("customer-a")
                .body("{\"amount\":10}")
                .send();

        assertError(missing, 400, IdempotencyError.KEY_REQUIRED);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
        assertThat(OrdersController.EXECUTIONS).hasValue(1);
    }

    private Http.Call order(String key) {
        return http.post("/orders").key(key).customer("customer-a").body(ORDER);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        JsonNode body = Http.json(response);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("errors").path(0).path("code").asString()).isEqualTo(code);
        assertThat(body.path("errors").path(0).path("message").asString()).isNotBlank();
        // El mensaje nunca cita la clave ni el cuerpo.
        assertThat(response.body()).doesNotContain("key-").doesNotContain("SKU-");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
            })
    @Import({OrdersController.class, PaymentsController.class})
    static class App {}
}
