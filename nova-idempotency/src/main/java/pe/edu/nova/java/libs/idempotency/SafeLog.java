package pe.edu.nova.java.libs.idempotency;

/**
 * Cómo se describe un fallo en el log sin citar la clave ni el cuerpo (ADR-047).
 *
 * <p>El mensaje de una {@link IdempotencyStoreException} es seguro por contrato. El de cualquier otra
 * excepción no: un almacén ajeno puede citar la fila, y el núcleo no lo sabe. De esas solo se registra el
 * tipo.
 */
final class SafeLog {

    private SafeLog() {}

    /**
     * Describe un fallo para el log.
     *
     * @param failure lo que falló
     * @return el mensaje, si el fallo es de un almacén; el nombre de la clase, si no
     */
    static String describe(RuntimeException failure) {
        if (failure instanceof IdempotencyStoreException) {
            return failure.getMessage();
        }
        return failure.getClass().getName() + " (message omitted)";
    }
}
