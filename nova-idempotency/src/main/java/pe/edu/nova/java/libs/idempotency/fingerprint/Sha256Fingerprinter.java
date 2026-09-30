package pe.edu.nova.java.libs.idempotency.fingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import pe.edu.nova.java.libs.idempotency.Fingerprinter;
import pe.edu.nova.java.libs.idempotency.IdempotentRequest;

/**
 * La huella por defecto de Nova (ADR-047): el SHA-256 del alcance, el método, la ruta y el cuerpo.
 *
 * <ul>
 *   <li>Un cuerpo JSON entra en su forma canónica de RFC 8785: claves ordenadas, sin espacios y con las
 *       cadenas y los números escritos de una sola manera. Así un cliente que reserializa el mismo JSON, con
 *       otro orden de claves o con otra sangría, no recibe un 422 falso.
 *   <li>Un cuerpo que no es JSON entra tal cual, byte por byte, y uno vacío entra como vacío. Un JSON y un
 *       texto con los mismos caracteres no se confunden.
 *   <li>Cada parte entra precedida de su largo en bytes, para que una parte no se corra a la siguiente:
 *       ({@code ab}, {@code c}) y ({@code a}, {@code bc}) dan huellas distintas.
 *   <li>El alcance forma parte de la huella, así que un registro que aparezca bajo la clave de otro cliente
 *       es un 422 y no una respuesta repetida.
 * </ul>
 *
 * <p>La ruta es la que da la petición: si distingue una query, la huella también. El resultado son 64
 * caracteres hexadecimales en minúscula, y no permite recuperar el cuerpo.
 */
public final class Sha256Fingerprinter implements Fingerprinter {

    private static final byte[] EMPTY_BODY = "empty".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JSON_BODY = "json".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RAW_BODY = "raw".getBytes(StandardCharsets.US_ASCII);

    /** Crea la huella. No tiene estado y sirve a varios hilos. */
    public Sha256Fingerprinter() {}

    @Override
    public String fingerprint(String scope, IdempotentRequest request) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(request, "request");
        MessageDigest digest = sha256();
        add(digest, utf8(scope));
        add(digest, utf8(request.method().toUpperCase(Locale.ROOT)));
        add(digest, utf8(request.path()));

        byte[] body = request.body();
        if (body.length == 0) {
            add(digest, EMPTY_BODY);
        } else {
            Optional<String> canonical = JsonCanonicalizer.canonicalize(body);
            if (canonical.isPresent()) {
                add(digest, JSON_BODY);
                add(digest, utf8(canonical.get()));
            } else {
                add(digest, RAW_BODY);
                add(digest, body);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Suma una parte precedida de su largo en bytes y dos puntos. */
    private static void add(MessageDigest digest, byte[] part) {
        digest.update((part.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(part);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has to provide SHA-256", e);
        }
    }
}
