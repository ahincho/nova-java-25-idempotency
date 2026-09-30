package pe.edu.nova.java.libs.idempotency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * La respuesta que se guarda al completar una operación y que se repite en cada reintento: el status, los
 * headers que se pueden repetir y el cuerpo, tal como salió.
 *
 * <p>Es inmutable: copia los headers y el cuerpo al crearse y al entregarlos, así que quien la guarda
 * puede conservarla sin copiarla. Los nombres de los headers se guardan en minúscula.
 *
 * <p>Su {@link #toString()} muestra el status, los nombres de los headers y el largo del cuerpo, nunca
 * el cuerpo ni los valores de los headers.
 *
 * @param status  el status HTTP, de 100 a 599
 * @param headers los headers de la respuesta, por nombre en minúscula; un header sin valores no existe
 * @param body    el cuerpo tal como se envió, vacío si no hubo
 */
public record StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {

    private static final int MIN_STATUS = 100;
    private static final int MAX_STATUS = 599;

    /**
     * Crea la respuesta, con una copia de los headers y del cuerpo.
     *
     * @throws IllegalArgumentException si el status no está entre 100 y 599 o un nombre de header está
     *                                  vacío
     */
    public StoredResponse {
        if (status < MIN_STATUS || status > MAX_STATUS) {
            throw new IllegalArgumentException("The status must be between " + MIN_STATUS + " and " + MAX_STATUS);
        }
        headers = copyHeaders(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    /**
     * Una respuesta sin headers.
     *
     * @param status el status HTTP
     * @param body   el cuerpo
     * @return la respuesta
     */
    public static StoredResponse of(int status, byte[] body) {
        return new StoredResponse(status, Map.of(), body);
    }

    /**
     * El cuerpo de la respuesta.
     *
     * @return una copia del cuerpo
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * El largo del cuerpo, sin copiarlo.
     *
     * @return la cantidad de bytes
     */
    public int bodyLength() {
        return body.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredResponse that
                && status == that.status
                && headers.equals(that.headers)
                && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(status, headers) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "StoredResponse[status=" + status + ", headers=" + headers.keySet() + ", bodyLength=" + body.length
                + "]";
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "header name")
                    .strip()
                    .toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A header name must not be empty");
            }
            List<String> values = merged.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (String value : Objects.requireNonNull(entry.getValue(), "header values")) {
                values.add(Objects.requireNonNull(value, "header value"));
            }
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        merged.forEach((name, values) -> {
            if (!values.isEmpty()) {
                copy.put(name, List.copyOf(values));
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
