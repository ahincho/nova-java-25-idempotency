package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Cómo se escribe en la respuesta un error del starter. Es un puerto: Nova trae
 * {@link NovaEnvelopeErrorResponder}, que usa el sobre de {@code nova-api-standard}, y un servicio lo
 * reemplaza declarando su propio bean.
 */
@FunctionalInterface
public interface IdempotencyErrorResponder {

    /**
     * Escribe el error. La respuesta todavía no está comprometida.
     *
     * @param response la respuesta
     * @param error    el error
     * @throws IOException si no se pudo escribir
     */
    void respond(HttpServletResponse response, IdempotencyError error) throws IOException;
}
