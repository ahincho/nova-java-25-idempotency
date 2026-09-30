package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.util.ContentCachingResponseWrapper;
import pe.edu.nova.java.libs.idempotency.StoredResponse;

/**
 * Guarda en memoria la respuesta de la operación para poder guardarla en el almacén antes de mandarla.
 *
 * <p>Una respuesta que la operación cerró con {@code sendError} la arma después el contenedor, con su
 * página de error, así que el starter nunca ve su cuerpo. Se marca para no guardarla: la clave se libera y el
 * cliente puede reintentar.
 */
final class CapturingResponse extends ContentCachingResponseWrapper {

    private boolean errorSent;

    CapturingResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void sendError(int status) throws IOException {
        errorSent = true;
        super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        errorSent = true;
        super.sendError(status, message);
    }

    /** Si la operación terminó con {@code sendError}. */
    boolean errorSent() {
        return errorSent;
    }

    /** La respuesta tal como la dejó la operación: el status, los headers y el cuerpo. */
    StoredResponse toStored() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : getHeaderNames()) {
            headers.put(name, List.copyOf(getHeaders(name)));
        }
        // El contenedor puede guardar el Content-Type aparte de los demás headers hasta que la respuesta sale.
        String contentType = getContentType();
        if (contentType != null && headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
            headers.put("content-type", List.of(contentType));
        }
        return new StoredResponse(getStatus(), headers, getContentAsByteArray());
    }
}
