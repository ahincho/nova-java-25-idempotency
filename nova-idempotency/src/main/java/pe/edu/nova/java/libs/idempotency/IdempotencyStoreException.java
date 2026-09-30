package pe.edu.nova.java.libs.idempotency;

/**
 * El almacén no pudo atender una llamada: no contestó a tiempo, perdió la conexión o rechazó la operación.
 *
 * <p>El mensaje dice qué operación falló y por qué, y <strong>nunca cita la clave, el alcance ni el
 * cuerpo</strong> (ADR-047). Por eso un adaptador no encadena una excepción cuyo mensaje pueda citarlos:
 * el servidor de una base de datos, por ejemplo, transcribe la fila cuando una restricción se rompe.
 */
public class IdempotencyStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crea la excepción sin causa.
     *
     * @param message qué operación falló y por qué, sin citar la clave, el alcance ni el cuerpo
     */
    public IdempotencyStoreException(String message) {
        super(message);
    }

    /**
     * Crea la excepción con una causa que no cita la clave, el alcance ni el cuerpo, como un timeout.
     *
     * @param message qué operación falló y por qué, sin citar la clave, el alcance ni el cuerpo
     * @param cause   la causa; nunca una excepción cuyo mensaje cite la fila
     */
    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
