package pe.edu.nova.java.libs.idempotency.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.edu.nova.java.libs.idempotency.IdempotentRequest;

class Sha256FingerprinterTest {

    private final Sha256Fingerprinter fingerprinter = new Sha256Fingerprinter();

    @Test
    void aFixedRequestKeepsItsFingerprint() {
        // Una huella guardada en la base tiene que seguir siendo comparable después de una versión nueva. Los
        // valores salen de calcular a mano el SHA-256 de las partes, cada una con su largo: 11:customer-42,
        // 4:POST, 10:/v1/orders, 4:json y el JSON canónico.
        assertThat(fingerprint("customer-42", "POST", "/v1/orders", "{\"b\":1,\"a\":[true,null]}"))
                .isEqualTo("9662785214a666f2323ac9a9f4c98fefc300e8c5326828e629ce35943780a55c");
        assertThat(fingerprint("customer-42", "POST", "/v1/orders", ""))
                .isEqualTo("57c5b78a9f8af7acab3103c7ab6e6248b60b3e620b1db5cf7ecdca4961336610");
        assertThat(fingerprint("customer-42", "POST", "/v1/orders", "a=1&b=2"))
                .isEqualTo("d6cf1eb9c946ca5dfbd9b4add81a2d3833f8d2ed5beab6aafddaf766bcc4d142");
    }

    @Test
    void theFingerprintIsSixtyFourLowerCaseHexadecimalCharacters() {
        assertThat(fingerprint("alice", "POST", "/orders", "{}")).matches("[0-9a-f]{64}");
    }

    @Test
    void theSameRequestGivesTheSameFingerprint() {
        assertThat(fingerprint("alice", "POST", "/orders", "{\"a\":1}"))
                .isEqualTo(fingerprint("alice", "POST", "/orders", "{\"a\":1}"));
    }

    @Test
    void reserializingTheSameJsonDoesNotChangeTheFingerprint() {
        String original = "{\"customer\":{\"name\":\"Ana\",\"age\":30},\"items\":[{\"sku\":\"A\",\"qty\":1.0}]}";
        String reserialized =
                " {\n \"items\" : [ { \"qty\" : 1 , \"sku\" : \"\\u0041\" } ],\n \"customer\": {\"age\":3e1,\"name\":\"Ana\"} }";

        assertThat(fingerprint("alice", "POST", "/orders", reserialized))
                .isEqualTo(fingerprint("alice", "POST", "/orders", original));
    }

    @Test
    void anotherScopeMethodPathOrBodyIsAnotherFingerprint() {
        String base = fingerprint("alice", "POST", "/orders", "{\"a\":1}");

        assertThat(fingerprint("bob", "POST", "/orders", "{\"a\":1}"))
                .as("scope")
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "PATCH", "/orders", "{\"a\":1}"))
                .as("method")
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "POST", "/orders/1", "{\"a\":1}"))
                .as("path")
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "POST", "/orders?x=1", "{\"a\":1}"))
                .as("query")
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "POST", "/orders", "{\"a\":2}"))
                .as("body")
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "POST", "/orders", "{\"a\":1,\"b\":null}"))
                .as("an extra property")
                .isNotEqualTo(base);
    }

    @Test
    void theMethodDoesNotDistinguishCase() {
        assertThat(fingerprint("alice", "post", "/orders", "{}"))
                .isEqualTo(fingerprint("alice", "POST", "/orders", "{}"));
    }

    @Test
    void partsCannotRunIntoEachOther() {
        assertThat(fingerprint("ab", "POST", "/c", "{}")).isNotEqualTo(fingerprint("a", "POST", "b/c", "{}"));
        assertThat(fingerprint("alice", "POST", "/orders/1", "{}"))
                .isNotEqualTo(fingerprint("alice/orders", "POST", "/1", "{}"));
    }

    @Test
    void theOrderOfAnArrayChangesTheFingerprint() {
        assertThat(fingerprint("alice", "POST", "/orders", "{\"items\":[1,2]}"))
                .isNotEqualTo(fingerprint("alice", "POST", "/orders", "{\"items\":[2,1]}"));
    }

    @Test
    void twoIdentifiersThatADoubleCannotTellApartAreStillTwoFingerprints() {
        assertThat(fingerprint("alice", "POST", "/orders", "{\"id\":9007199254740993}"))
                .isNotEqualTo(fingerprint("alice", "POST", "/orders", "{\"id\":9007199254740992}"));
    }

    @Test
    void aBodyThatIsNotJsonIsTakenByteForByte() {
        assertThat(fingerprint("alice", "POST", "/forms", "a=1&b=2"))
                .isEqualTo(fingerprint("alice", "POST", "/forms", "a=1&b=2"));
        assertThat(fingerprint("alice", "POST", "/forms", "a=1&b=2"))
                .isNotEqualTo(fingerprint("alice", "POST", "/forms", "b=2&a=1"));
        assertThat(new Sha256Fingerprinter().fingerprint("alice", IdempotentRequest.of("POST", "/x", new byte[] {
                    (byte) 0xFF, 0x00, 0x7F
                })))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void aJsonBodyAndATextWithTheSameCharactersAreNotConfused() {
        // "abc" con comillas es un JSON; abc sin ellas, un texto.
        assertThat(fingerprint("alice", "POST", "/x", "\"abc\""))
                .isNotEqualTo(fingerprint("alice", "POST", "/x", "abc"));
        assertThat(fingerprint("alice", "POST", "/x", "null")).isNotEqualTo(fingerprint("alice", "POST", "/x", ""));
    }

    @Test
    void headersAndTheIdentityDoNotChangeTheFingerprint() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        IdempotentRequest plain = IdempotentRequest.of("POST", "/orders", body);
        IdempotentRequest decorated = IdempotentRequest.of(
                "POST", "/orders", Map.of("X-Customer-Id", "42", "Idempotency-Key", "k"), "alice", body);

        assertThat(fingerprinter.fingerprint("alice", decorated)).isEqualTo(fingerprinter.fingerprint("alice", plain));
    }

    private String fingerprint(String scope, String method, String path, String body) {
        return fingerprinter.fingerprint(
                scope, IdempotentRequest.of(method, path, body.getBytes(StandardCharsets.UTF_8)));
    }
}
