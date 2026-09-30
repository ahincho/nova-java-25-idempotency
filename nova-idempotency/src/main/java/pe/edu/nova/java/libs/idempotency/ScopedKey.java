package pe.edu.nova.java.libs.idempotency;

import java.util.Objects;

/**
 * Una clave de idempotencia dentro de su alcance: lo que identifica un registro en el almacén.
 *
 * <p>El alcance siempre forma parte de la clave (ADR-047). La misma clave de dos clientes son dos
 * registros distintos, y un cliente nunca recibe la respuesta de otro. Por eso el alcance no puede ser
 * vacío: no existe una clave global.
 *
 * <p>Las dos partes se comparan exactamente, sin normalizar: {@code k} y {@code K}, o {@code k} y
 * {@code k } con un espacio al final, son claves distintas, y el par ({@code a:b}, {@code c}) no se
 * confunde con ({@code a}, {@code b:c}).
 *
 * <p>Su {@link #toString()} no muestra ninguna de las dos partes, para que un registro que termina en un
 * log por descuido no filtre la clave ni de quién es.
 *
 * @param scope de quién es la clave, como la identidad autenticada del cliente; no puede ser vacío ni
 *              pasar de 255 caracteres, ni llevar caracteres de control
 * @param key   la clave que mandó el cliente, de 1 a 255 caracteres ASCII imprimibles, tal como llegó
 */
public record ScopedKey(String scope, String key) {

    /** El largo máximo del alcance, para que la clave de un registro quepa en el índice de cualquier almacén. */
    public static final int MAX_SCOPE_LENGTH = 255;

    /**
     * Crea la clave.
     *
     * @throws IllegalArgumentException si el alcance está vacío o es demasiado largo, o si la clave no es
     *                                  válida; el mensaje no cita ninguno de los dos valores
     */
    public ScopedKey {
        checkScope(scope);
        Objects.requireNonNull(key, "key");
        if (!IdempotencyKeys.isValid(key)) {
            throw new IllegalArgumentException(
                    "The key must have 1 to " + IdempotencyKeys.MAX_LENGTH + " printable ASCII characters");
        }
    }

    /**
     * Comprueba que un alcance sirva, sin citarlo en el error.
     *
     * @param scope el alcance
     * @throws IllegalArgumentException si está vacío, es demasiado largo o lleva caracteres de control
     */
    static void checkScope(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("The scope must not be empty: an idempotency key is never global");
        }
        if (scope.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("The scope must not be longer than " + MAX_SCOPE_LENGTH + " characters");
        }
        if (scope.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The scope must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return "ScopedKey[redacted]";
    }
}
