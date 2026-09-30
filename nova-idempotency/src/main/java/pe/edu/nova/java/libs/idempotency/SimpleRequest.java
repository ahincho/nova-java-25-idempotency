package pe.edu.nova.java.libs.idempotency;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Una petición ya leída: los datos de {@link IdempotentRequest} en memoria, sin mirar ningún framework. */
final class SimpleRequest implements IdempotentRequest {

    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final String principal;
    private final byte[] body;

    SimpleRequest(String method, String path, Map<String, String> headers, String principal, byte[] body) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        Map<String, String> lowerCased = new TreeMap<>();
        Objects.requireNonNull(headers, "headers")
                .forEach((name, value) -> lowerCased.put(name.toLowerCase(Locale.ROOT), value));
        this.headers = Map.copyOf(lowerCased);
        this.principal = principal;
        this.body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<String> principal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "IdempotentRequest[" + method + "]";
    }
}
