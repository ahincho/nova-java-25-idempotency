/**
 * El contrato de la capacidad de idempotencia de Nova (ADR-047): un cliente puede reintentar una
 * operación sin que se ejecute dos veces.
 *
 * <p>El núcleo no habla HTTP. {@link pe.edu.nova.java.libs.idempotency.IdempotencyEngine} recibe la
 * clave, el alcance y la huella de una petición y decide una
 * {@link pe.edu.nova.java.libs.idempotency.Decision}: ejecutar la operación, repetir la respuesta
 * guardada, avisar que la clave sigue en curso, que llegó con otro contenido, que falta o que no es
 * válida. El conector de cada framework traduce esas salidas a respuestas.
 *
 * <p>Lo que una organización puede reemplazar sin forkear es un puerto:
 * {@link pe.edu.nova.java.libs.idempotency.IdempotencyStore} guarda los registros,
 * {@link pe.edu.nova.java.libs.idempotency.ScopeResolver} dice de quién es la clave y
 * {@link pe.edu.nova.java.libs.idempotency.Fingerprinter} calcula la huella de la petición. Nova trae
 * una implementación de cada uno; los almacenes viven en sus propios paquetes y módulos.
 *
 * <p>El paquete no importa nada de Spring, de Jakarta ni de ningún otro framework (ADR-015), para que el
 * mismo JAR sirva en cualquiera.
 */
package pe.edu.nova.java.libs.idempotency;
