package pe.edu.nova.java.libs.idempotency;

import java.util.Objects;

/**
 * Lo que encontró {@link IdempotencyStore#acquire}: la clave era libre y ahora es del intento, o ya tenía
 * un dueño o una respuesta.
 */
public sealed interface Acquisition {

    /** La clave estaba libre, o su lock o su registro habían vencido: ahora es del intento que la pidió. */
    record Acquired() implements Acquisition {}

    /**
     * La clave tiene un lock vigente: otro intento la está ejecutando.
     *
     * @param fingerprint la huella con que ese intento tomó la clave, no la de quien preguntó
     */
    record InFlight(String fingerprint) implements Acquisition {

        /**
         * Crea el resultado.
         *
         * @throws NullPointerException si la huella es nula
         */
        public InFlight {
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        @Override
        public String toString() {
            return "InFlight[redacted]";
        }
    }

    /**
     * La clave ya tiene una respuesta guardada y vigente.
     *
     * @param fingerprint la huella con que se ejecutó la operación, no la de quien preguntó
     * @param response    la respuesta guardada
     */
    record Completed(String fingerprint, StoredResponse response) implements Acquisition {

        /**
         * Crea el resultado.
         *
         * @throws NullPointerException si la huella o la respuesta son nulas
         */
        public Completed {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(response, "response");
        }

        @Override
        public String toString() {
            return "Completed[" + response + "]";
        }
    }
}
