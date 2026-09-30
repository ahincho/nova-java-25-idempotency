package pe.edu.nova.java.starters.idempotency;

import java.security.Principal;
import java.util.Optional;
import pe.edu.nova.java.libs.idempotency.IdempotentRequest;

/**
 * La vista de una petición de servlet que reciben el {@code ScopeResolver} y el {@code Fingerprinter}.
 *
 * <p>La ruta es la concreta y lleva la query, si la hay: la misma clave con otra query es otro contenido.
 */
final class ServletIdempotentRequest implements IdempotentRequest {

    private final CachedBodyRequest request;

    ServletIdempotentRequest(CachedBodyRequest request) {
        this.request = request;
    }

    @Override
    public String method() {
        return request.getMethod();
    }

    @Override
    public String path() {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(request.getHeader(name));
    }

    @Override
    public Optional<String> principal() {
        Principal principal = request.getUserPrincipal();
        return principal == null ? Optional.empty() : Optional.ofNullable(principal.getName());
    }

    @Override
    public byte[] body() {
        return request.body();
    }

    @Override
    public String toString() {
        // Ni la clave ni el cuerpo aparecen en un log (ADR-047).
        return "IdempotentRequest[" + request.getMethod() + "]";
    }
}
