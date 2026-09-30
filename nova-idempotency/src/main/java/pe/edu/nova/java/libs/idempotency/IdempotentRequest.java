package pe.edu.nova.java.libs.idempotency;

import java.util.Map;
import java.util.Optional;

/**
 * Lo que el núcleo ve de una petición, sin depender de ningún framework: la vista que reciben un
 * {@link ScopeResolver} y un {@link Fingerprinter}.
 *
 * <p>La implementa el conector de cada framework sobre su propia petición. Así una organización escribe su
 * resolvedor de alcance o su huella una sola vez y le sirven en Spring Boot y en Quarkus.
 */
public interface IdempotentRequest {

    /**
     * El método HTTP.
     *
     * @return el método, como {@code POST}
     */
    String method();

    /**
     * La ruta concreta de la petición, no la plantilla de la ruta: {@code /orders/42}, no
     * {@code /orders/{id}}. Si la operación depende de la query, el conector la incluye.
     *
     * @return la ruta
     */
    String path();

    /**
     * El valor de un header.
     *
     * @param name el nombre, sin distinguir mayúsculas
     * @return el primer valor, o vacío si la petición no lo trae
     */
    Optional<String> header(String name);

    /**
     * La identidad autenticada de quien hace la petición.
     *
     * @return el nombre de la identidad, o vacío si la petición no está autenticada
     */
    Optional<String> principal();

    /**
     * El cuerpo de la petición.
     *
     * @return los bytes del cuerpo, vacíos si no hay
     */
    byte[] body();

    /**
     * Una petición ya leída, para los conectores que no tienen un framework y para las pruebas.
     *
     * @param method    el método HTTP
     * @param path      la ruta concreta
     * @param headers   los headers, con un solo valor cada uno; los nombres no distinguen mayúsculas
     * @param principal la identidad autenticada, o {@code null} si la petición no lo está
     * @param body      el cuerpo, o {@code null} si no hay
     * @return la petición, inmutable
     */
    static IdempotentRequest of(
            String method, String path, Map<String, String> headers, String principal, byte[] body) {
        return new SimpleRequest(method, path, headers, principal, body);
    }

    /**
     * Una petición ya leída, sin headers y sin identidad.
     *
     * @param method el método HTTP
     * @param path   la ruta concreta
     * @param body   el cuerpo, o {@code null} si no hay
     * @return la petición, inmutable
     */
    static IdempotentRequest of(String method, String path, byte[] body) {
        return new SimpleRequest(method, path, Map.of(), null, body);
    }
}
