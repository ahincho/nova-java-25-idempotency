package pe.edu.nova.java.libs.idempotency;

import java.util.Objects;
import java.util.Optional;

/**
 * De quién es la clave: el puerto que decide el alcance de una petición (ADR-047).
 *
 * <p>La misma clave de dos clientes son dos claves distintas, y un cliente nunca recibe la respuesta de
 * otro. Por eso el alcance <strong>nunca es global</strong>: si no se puede resolver, la petición no
 * puede llevar idempotencia, y el núcleo rechaza un alcance vacío.
 *
 * <p>Nova trae las dos implementaciones que no dependen de un framework: {@link #ofPrincipal()}, la identidad
 * autenticada, y {@link #ofHeader}, un header como el {@code X-Customer-Id} que pasa el BFF de Plaza. Una
 * organización pone la suya sin forkear.
 */
@FunctionalInterface
public interface ScopeResolver {

    /**
     * Resuelve de quién es la clave.
     *
     * @param request la petición
     * @return el alcance, o vacío si no se puede determinar
     */
    Optional<String> resolve(IdempotentRequest request);

    /**
     * El alcance es la identidad autenticada de la petición.
     *
     * @return el resolvedor; devuelve vacío si la petición no está autenticada o su identidad está en blanco
     */
    static ScopeResolver ofPrincipal() {
        return request -> request.principal().map(String::strip).filter(name -> !name.isEmpty());
    }

    /**
     * El alcance es el valor de un header, como el que un BFF agrega después de autenticar al cliente.
     *
     * @param name el nombre del header, como {@code X-Customer-Id}
     * @return el resolvedor; devuelve vacío si la petición no trae el header o lo trae en blanco
     */
    static ScopeResolver ofHeader(String name) {
        Objects.requireNonNull(name, "name");
        return request -> request.header(name).map(String::strip).filter(value -> !value.isEmpty());
    }
}
