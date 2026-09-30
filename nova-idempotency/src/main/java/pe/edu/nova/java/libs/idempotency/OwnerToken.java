package pe.edu.nova.java.libs.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * El token de dueño de un intento: la ficha con la que completa, libera y renueva su registro.
 *
 * <p>Cada intento de ejecutar una operación toma uno nuevo. Si el lock de un intento vence y otro toma la
 * clave, las llamadas del primero llevan un token que ya no es el del registro y el almacén las rechaza
 * (ADR-047). Es lo que evita que un intento lento pise el registro del que lo reemplazó.
 *
 * <p>Su {@link #toString()} no muestra el valor.
 *
 * @param value el valor del token, opaco; de 1 a 64 caracteres
 */
public record OwnerToken(String value) {

    private static final int MAX_LENGTH = 64;

    /**
     * Crea el token.
     *
     * @throws IllegalArgumentException si el valor está vacío o pasa de 64 caracteres
     */
    public OwnerToken {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The token must have 1 to " + MAX_LENGTH + " characters");
        }
    }

    /**
     * Crea un token nuevo, distinto de cualquier otro.
     *
     * @return un token aleatorio de 122 bits
     */
    public static OwnerToken generate() {
        return new OwnerToken(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return "OwnerToken[redacted]";
    }
}
