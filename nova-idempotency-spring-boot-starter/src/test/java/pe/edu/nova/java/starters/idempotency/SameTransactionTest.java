package pe.edu.nova.java.starters.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La operación y el registro de su respuesta se confirman en el mismo commit, contra un PostgreSQL 17 real y
 * el gestor de transacciones de Spring. Se salta si la máquina no tiene Docker.
 */
@SpringBootTest(
        classes = SameTransactionTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nova.idempotency.scope-header=X-Customer-Id")
class SameTransactionTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String ORDER = "{\"sku\":\"SKU-1\"}";

    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch proceed = new CountDownLatch(1);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private Http http;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists orders (id serial primary key, sku text not null)");
        if (jdbc.queryForObject("select to_regclass('idempotency_record') is null", Boolean.class)) {
            jdbc.execute(schemaScript());
        }
        jdbc.execute("truncate table orders, idempotency_record");
        entered = new CountDownLatch(1);
        proceed = new CountDownLatch(1);
        http = new Http(port);
    }

    @Test
    void theOrderAndItsStoredResponseAreCommittedTogether() {
        HttpResponse<String> response = post("/tx/orders", "key-commit").send();

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(count("orders")).isEqualTo(1);
        Map<String, Object> record = jdbc.queryForMap("select owner_token, status from idempotency_record");
        assertThat(record.get("owner_token")).isNull();
        assertThat(((Number) record.get("status")).intValue()).isEqualTo(201);

        HttpResponse<String> retry = post("/tx/orders", "key-commit").send();
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void aServerErrorUndoesTheOrderAndReleasesTheKey() {
        HttpResponse<String> response = post("/tx/orders/fail", "key-fail").send();

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(count("orders")).as("the order was rolled back").isZero();
        assertThat(count("idempotency_record")).as("the key was released").isZero();
    }

    @Test
    void aBusinessErrorUndoesTheOrderButItsResponseIsStoredAndReplayed() {
        HttpResponse<String> response =
                post("/tx/orders/out-of-stock", "key-stock").send();
        HttpResponse<String> retry =
                post("/tx/orders/out-of-stock", "key-stock").send();

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(count("orders")).isZero();
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER))
                .hasValue("true");
    }

    @Test
    void theLockIsVisibleWhileTheBusinessTransactionIsStillOpen() throws Exception {
        CompletableFuture<HttpResponse<String>> first =
                post("/tx/orders/slow", "key-slow").sendAsync();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> duplicate = post("/tx/orders/slow", "key-slow").send();
        proceed.countDown();

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(Http.json(duplicate).path("errors").path(0).path("code").asString())
                .isEqualTo(IdempotencyError.KEY_IN_USE);
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(count("orders")).isEqualTo(1);
    }

    private Http.Call post(String path, String key) {
        return http.post(path).key(key).customer("customer-a").body(ORDER);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static String schemaScript() {
        try (InputStream script = SameTransactionTest.class.getResourceAsStream(
                "/db/nova/idempotency/postgresql/V1__create_idempotency_record.sql")) {
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** El negocio: guarda el pedido en su propia transacción, que se suma a la del filtro. */
    @Service
    static class OrderService {

        private final JdbcTemplate jdbc;

        OrderService(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        void place(String sku) {
            jdbc.update("insert into orders (sku) values (?)", sku);
        }

        @Transactional
        void placeWithoutStock(String sku) {
            jdbc.update("insert into orders (sku) values (?)", sku);
            throw new OutOfStock();
        }
    }

    /** Un error del negocio, que el controlador responde con 409. */
    static final class OutOfStock extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    @RestController
    static class TxOrdersController {

        private final OrderService service;

        TxOrdersController(OrderService service) {
            this.service = service;
        }

        @Idempotent
        @PostMapping("/tx/orders")
        ResponseEntity<Map<String, Object>> place(@RequestBody Map<String, Object> order) {
            service.place((String) order.get("sku"));
            return ResponseEntity.status(201).body(Map.of("placed", true));
        }

        @Idempotent
        @PostMapping("/tx/orders/fail")
        ResponseEntity<Map<String, Object>> fail(@RequestBody Map<String, Object> order) {
            service.place((String) order.get("sku"));
            return ResponseEntity.status(500).body(Map.of("placed", false));
        }

        @Idempotent
        @PostMapping("/tx/orders/out-of-stock")
        ResponseEntity<Map<String, Object>> outOfStock(@RequestBody Map<String, Object> order) {
            service.placeWithoutStock((String) order.get("sku"));
            return ResponseEntity.status(201).body(Map.of("placed", true));
        }

        @Idempotent
        @PostMapping("/tx/orders/slow")
        ResponseEntity<Map<String, Object>> slow(@RequestBody Map<String, Object> order) throws InterruptedException {
            service.place((String) order.get("sku"));
            entered.countDown();
            proceed.await(10, TimeUnit.SECONDS);
            return ResponseEntity.status(201).body(Map.of("placed", true));
        }

        @ExceptionHandler(OutOfStock.class)
        ResponseEntity<Map<String, Object>> outOfStock(OutOfStock failure) {
            return ResponseEntity.status(409).body(Map.of("error", "out of stock"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({OrderService.class, TxOrdersController.class})
    static class App {}
}
