/**
 * La huella por defecto de una petición: el SHA-256 del alcance, el método, la ruta y el cuerpo.
 *
 * <p>Un cuerpo JSON se canoniza antes de calcular la huella, con las reglas de RFC 8785: claves ordenadas,
 * sin espacios, con las mismas comillas y los mismos números, para que un cliente que reserializa el mismo
 * JSON no reciba un 422 falso. Solo {@code JsonCanonicalizer} lee JSON, con el parser de streaming de
 * Jackson 2, que convive con el Jackson 3 de Spring Boot 4.
 */
package pe.edu.nova.java.libs.idempotency.fingerprint;
