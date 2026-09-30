package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import pe.edu.nova.java.libs.api.standard.error.ApiError;
import pe.edu.nova.java.libs.api.standard.response.ApiResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * La implementación de Nova de {@link IdempotencyErrorResponder}: el error sale en el sobre
 * {@link ApiResponse} de la plataforma, con un solo {@link ApiError}, y un 409 lleva además el header
 * {@code Retry-After}.
 *
 * <p>Usa el {@link JsonMapper} del servicio, así que el cuerpo sale igual que el de cualquier otro error del
 * servicio que use el mismo sobre.
 */
public final class NovaEnvelopeErrorResponder implements IdempotencyErrorResponder {

    private final JsonMapper mapper;

    /**
     * Crea el responder.
     *
     * @param mapper el mapper JSON del servicio
     */
    public NovaEnvelopeErrorResponder(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void respond(HttpServletResponse response, IdempotencyError error) throws IOException {
        ApiResponse<Void> body = ApiResponse.error(error.status(), List.of(ApiError.of(error.code(), error.message())));
        byte[] json = mapper.writeValueAsBytes(body);
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (error.retryAfter() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(error)));
        }
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }

    /** {@code Retry-After} va en segundos enteros; una espera menor que un segundo se redondea hacia arriba. */
    private static long retryAfterSeconds(IdempotencyError error) {
        long millis = error.retryAfter().toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
