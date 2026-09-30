package pe.edu.nova.java.starters.idempotency;

import java.time.Duration;
import java.util.Objects;

/**
 * Un error que el starter responde sin llegar a la operación: la clave falta, no es válida, sigue en curso o
 * llegó con otro contenido, el alcance no se pudo resolver, o el almacén no respondió.
 *
 * @param status     el status HTTP
 * @param code       el código del error, como {@code IDEMPOTENCY_KEY_IN_USE}
 * @param message    el mensaje para el cliente, que nunca cita la clave ni el cuerpo
 * @param retryAfter cuánto conviene esperar antes de reintentar, o {@code null} si no aplica
 */
public record IdempotencyError(int status, String code, String message, Duration retryAfter) {

    /** Falta la clave en una operación que la exige. */
    public static final String KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";

    /** La clave no tiene entre 1 y 255 caracteres ASCII imprimibles. */
    public static final String KEY_INVALID = "IDEMPOTENCY_KEY_INVALID";

    /** Una petición con la misma clave sigue en curso. */
    public static final String KEY_IN_USE = "IDEMPOTENCY_KEY_IN_USE";

    /** La misma clave llegó con otro contenido. */
    public static final String KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    /**
     * Valida el error.
     *
     * @throws IllegalArgumentException si el status no es de error
     */
    public IdempotencyError {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("An error status must be between 400 and 599");
        }
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    static IdempotencyError keyRequired() {
        return new IdempotencyError(400, KEY_REQUIRED, "Falta el header Idempotency-Key", null);
    }

    static IdempotencyError keyInvalid() {
        return new IdempotencyError(
                400,
                KEY_INVALID,
                "El header Idempotency-Key debe tener entre 1 y 255 caracteres ASCII imprimibles",
                null);
    }

    static IdempotencyError keyInUse(Duration retryAfter) {
        return new IdempotencyError(
                409, KEY_IN_USE, "Una solicitud con la misma clave de idempotencia sigue en curso", retryAfter);
    }

    static IdempotencyError keyReused() {
        return new IdempotencyError(422, KEY_REUSED, "La clave de idempotencia ya se usó con otra solicitud", null);
    }

    static IdempotencyError scopeHeaderMissing(String header) {
        return new IdempotencyError(400, "BAD_REQUEST", "Falta el header " + header, null);
    }

    static IdempotencyError unauthenticated() {
        return new IdempotencyError(401, "UNAUTHORIZED", "Hace falta autenticarse", null);
    }

    static IdempotencyError storeUnavailable() {
        return new IdempotencyError(503, "SERVICE_UNAVAILABLE", "El servicio no está disponible en este momento", null);
    }
}
