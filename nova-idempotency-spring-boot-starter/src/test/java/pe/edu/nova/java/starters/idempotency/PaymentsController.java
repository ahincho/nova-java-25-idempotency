package pe.edu.nova.java.starters.idempotency;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Un controlador con {@link Idempotent} sobre la clase: vale para todas sus operaciones. */
@Idempotent
@RestController
class PaymentsController {

    @PostMapping("/payments")
    Map<String, Object> pay(@RequestBody Map<String, Object> payment) {
        return Map.of("paid", payment.get("amount"), "execution", OrdersController.EXECUTIONS.incrementAndGet());
    }
}
