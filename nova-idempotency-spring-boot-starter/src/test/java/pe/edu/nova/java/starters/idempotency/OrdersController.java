package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Un controlador de pedidos de mentira, que cuenta cuántas veces corrió cada operación. */
@RestController
@RequestMapping("/orders")
class OrdersController {

    static final AtomicInteger EXECUTIONS = new AtomicInteger();
    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch proceed = new CountDownLatch(1);

    static void reset() {
        EXECUTIONS.set(0);
        entered = new CountDownLatch(1);
        proceed = new CountDownLatch(1);
    }

    @Idempotent
    @PostMapping
    ResponseEntity<Map<String, Object>> place(@RequestBody Map<String, Object> order) {
        int id = EXECUTIONS.incrementAndGet();
        return ResponseEntity.created(URI.create("/orders/" + id))
                .header(HttpHeaders.SET_COOKIE, "session=abc")
                .header("X-Order-Trace", "trace-" + id)
                .body(Map.of("id", id, "sku", order.get("sku")));
    }

    @Idempotent
    @PostMapping("/slow")
    ResponseEntity<Map<String, Object>> slow(@RequestBody Map<String, Object> order) throws InterruptedException {
        int id = EXECUTIONS.incrementAndGet();
        entered.countDown();
        proceed.await(10, TimeUnit.SECONDS);
        return ResponseEntity.status(201).body(Map.of("id", id));
    }

    @Idempotent
    @PostMapping("/fail")
    ResponseEntity<Map<String, Object>> fail(@RequestBody Map<String, Object> order) {
        EXECUTIONS.incrementAndGet();
        return ResponseEntity.status(503).body(Map.of("error", "down"));
    }

    @Idempotent
    @PostMapping("/throw")
    ResponseEntity<Map<String, Object>> explode(@RequestBody Map<String, Object> order) {
        EXECUTIONS.incrementAndGet();
        throw new IllegalStateException("the operation failed");
    }

    @Idempotent
    @PostMapping("/rejected")
    ResponseEntity<Map<String, Object>> rejected(@RequestBody Map<String, Object> order) {
        EXECUTIONS.incrementAndGet();
        return ResponseEntity.unprocessableContent().body(Map.of("error", "out of stock"));
    }

    @Idempotent
    @PostMapping("/needs-param")
    ResponseEntity<Map<String, Object>> needsParam(@RequestParam("channel") String channel) {
        EXECUTIONS.incrementAndGet();
        return ResponseEntity.ok(Map.of("channel", channel));
    }

    @Idempotent
    @PostMapping("/send-error")
    void sendError(HttpServletResponse response) throws java.io.IOException {
        EXECUTIONS.incrementAndGet();
        response.sendError(409, "conflict");
    }

    @PostMapping("/plain")
    Map<String, Object> plain(@RequestBody Map<String, Object> order) {
        return Map.of("executions", EXECUTIONS.incrementAndGet());
    }

    @GetMapping
    Map<String, Object> list() {
        return Map.of("executions", EXECUTIONS.get());
    }
}
