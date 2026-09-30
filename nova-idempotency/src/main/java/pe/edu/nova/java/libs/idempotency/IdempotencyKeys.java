package pe.edu.nova.java.libs.idempotency;

/**
 * Las reglas de la clave que manda el cliente (ADR-047): de 1 a 255 caracteres ASCII imprimibles.
 *
 * <p>La clave se toma tal cual llega. No se recorta ni se le quitan las comillas, así que {@code "abc"}
 * y {@code abc} son dos claves distintas.
 */
final class IdempotencyKeys {

    /** El largo máximo de una clave. */
    static final int MAX_LENGTH = 255;

    private static final char FIRST_PRINTABLE = 0x20;
    private static final char LAST_PRINTABLE = 0x7E;

    private IdempotencyKeys() {}

    /**
     * Dice si un texto es una clave válida.
     *
     * @param key el texto, sin ninguna transformación
     * @return {@code true} si tiene entre 1 y 255 caracteres y todos son ASCII imprimibles (del espacio a
     *         la tilde)
     */
    static boolean isValid(String key) {
        int length = key.length();
        if (length == 0 || length > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE) {
                return false;
            }
        }
        return true;
    }
}
