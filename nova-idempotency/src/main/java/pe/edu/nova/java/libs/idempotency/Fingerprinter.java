package pe.edu.nova.java.libs.idempotency;

/**
 * La huella de una petición: el puerto que decide cuándo dos peticiones con la misma clave son la misma
 * (ADR-047).
 *
 * <p>Si la misma clave llega con otra huella, es otro contenido y la respuesta es un 422. La
 * implementación por defecto, {@link pe.edu.nova.java.libs.idempotency.fingerprint.Sha256Fingerprinter},
 * calcula el SHA-256 del alcance, el método, la ruta y el cuerpo JSON canónico. La huella tiene que ser
 * determinista y no filtrar el contenido: es lo que se guarda junto a la clave.
 */
@FunctionalInterface
public interface Fingerprinter {

    /**
     * Calcula la huella de una petición.
     *
     * @param scope   de quién es la clave; forma parte de la huella, para que un registro que aparezca
     *                bajo la clave de otro cliente sea un 422 y no una respuesta repetida
     * @param request la petición
     * @return la huella, un texto de hasta 255 caracteres
     */
    String fingerprint(String scope, IdempotentRequest request);
}
