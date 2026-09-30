package pe.edu.nova.java.starters.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una operación de un controlador como idempotente (ADR-047): la petición tiene que traer el header
 * {@code Idempotency-Key}, y un reintento con la misma clave recibe la respuesta que ya se dio en vez de
 * ejecutar la operación otra vez.
 *
 * <p>Es opcional por operación: el starter no protege ningún POST por su cuenta. Puesta sobre la clase, vale
 * para todas sus operaciones.
 *
 * <pre>{@code
 * @Idempotent
 * @PostMapping("/v1/orders")
 * ResponseEntity<OrderResponse> place(@RequestBody CreateOrderRequest request) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Idempotent {}
