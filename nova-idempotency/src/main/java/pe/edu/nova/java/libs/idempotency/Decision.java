package pe.edu.nova.java.libs.idempotency;

import java.time.Duration;
import java.util.Objects;

/**
 * Lo que el núcleo decide para una petición con clave de idempotencia (ADR-047).
 *
 * <p>El núcleo devuelve resultados y no habla HTTP. El conector de cada framework los traduce: ejecutar
 * es dejar pasar la operación, repetir es contestar con la respuesta guardada y las demás salidas son los
 * errores de la tabla del ADR.
 */
public sealed interface Decision {

    /**
     * La clave es de este intento: hay que ejecutar la operación. Después, el conector la completa con
     * {@link IdempotencyEngine#complete} o, si no hay una respuesta que guardar, la libera con
     * {@link IdempotencyEngine#release}; mientras tanto el núcleo renueva el lock solo.
     *
     * <p>Hay que cerrar todo intento con una de las dos. Un intento que no se cierra sigue renovando su lock
     * hasta que se cierra el motor, y su clave queda tomada.
     *
     * @param attempt el intento, con su token de dueño
     */
    record Execute(Attempt attempt) implements Decision {

        /**
         * Crea la decisión.
         *
         * @throws NullPointerException si el intento es nulo
         */
        public Execute {
            Objects.requireNonNull(attempt, "attempt");
        }
    }

    /**
     * La clave ya se respondió con el mismo contenido: hay que repetir la respuesta guardada, sin
     * ejecutar la operación. Solo trae los headers que se pueden repetir.
     *
     * @param response la respuesta guardada
     */
    record Replay(StoredResponse response) implements Decision {

        /**
         * Crea la decisión.
         *
         * @throws NullPointerException si la respuesta es nula
         */
        public Replay {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * La misma clave, con el mismo contenido, sigue en curso: será un 409 con {@code Retry-After}.
     *
     * @param retryAfter cuánto conviene esperar antes de reintentar
     */
    record InProgress(Duration retryAfter) implements Decision {

        /**
         * Crea la decisión.
         *
         * @throws NullPointerException si la espera es nula
         */
        public InProgress {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        /**
         * La espera para el header {@code Retry-After}, que va en segundos enteros.
         *
         * @return los segundos, redondeados hacia arriba y nunca menos de 1
         */
        public long retryAfterSeconds() {
            long millis = retryAfter.toMillis();
            return Math.max(1, (millis + 999) / 1000);
        }
    }

    /** La misma clave llegó con otro contenido: será un 422. */
    record KeyReused() implements Decision {}

    /** Falta la clave en una operación que la exige: será un 400. */
    record KeyMissing() implements Decision {}

    /** La clave no tiene entre 1 y 255 caracteres ASCII imprimibles: será un 400. */
    record KeyInvalid() implements Decision {}
}
