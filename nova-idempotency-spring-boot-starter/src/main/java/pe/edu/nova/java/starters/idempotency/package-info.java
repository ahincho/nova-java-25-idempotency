/**
 * El conector de la capacidad de idempotencia con Spring Boot (ADR-047).
 *
 * <p>Una operación de un controlador con {@link pe.edu.nova.java.starters.idempotency.Idempotent} exige el
 * header {@code Idempotency-Key}, y el starter traduce a HTTP cada salida del núcleo: 400 si la clave falta o
 * no es válida, 409 con {@code Retry-After} si sigue en curso, 422 si llegó con otro contenido y la respuesta
 * guardada, con {@code Idempotent-Replayed: true}, si ya se respondió.
 *
 * <p>La configuración va bajo {@code nova.idempotency.*}, y cada pieza es un bean que el servicio reemplaza:
 * el almacén, el alcance, la huella y la forma de los errores.
 */
package pe.edu.nova.java.starters.idempotency;
