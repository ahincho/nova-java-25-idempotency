package pe.edu.nova.java.libs.idempotency.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCanonicalizerTest {

    @Test
    void theExampleOfRfc8785IsCanonicalizedAsInTheRfc() throws IOException {
        byte[] input;
        try (InputStream resource = getClass().getResourceAsStream("/rfc8785/sample-input.json")) {
            input = resource.readAllBytes();
        }

        // La sección 3.2.4 del RFC: propiedades ordenadas, números como ECMAScript y cadenas con el mínimo de escapes.
        String expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                + "\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}";
        assertThat(canonical(input)).isEqualTo(expected);
    }

    @Test
    void propertiesAreSortedByUtf16CodeUnitsAsInSection323OfRfc8785() {
        String json =
                "{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"\\ufb33\":\"Hebrew Letter Dalet With Dagesh\","
                        + "\"1\":\"One\",\"\\ud83d\\ude00\":\"Emoji: Grinning Face\",\"\\u0080\":\"Control\","
                        + "\"\\u00f6\":\"Latin Small Letter O With Diaeresis\"}";

        String canonical = canonical(json);

        // \r, 1, U+0080, U+00F6, U+20AC, U+1F600 (un par sustituto, D83D), U+FB33
        assertThat(canonical)
                .containsSubsequence(
                        "\"Carriage Return\"",
                        "\"One\"",
                        "\"Control\"",
                        "\"Latin Small Letter O With Diaeresis\"",
                        "\"Euro Sign\"",
                        "\"Emoji: Grinning Face\"",
                        "\"Hebrew Letter Dalet With Dagesh\"");
        assertThat(canonical).startsWith("{\"\\r\":\"Carriage Return\",\"1\":\"One\"");
    }

    @Test
    void whitespaceAndKeyOrderDoNotMatter() {
        assertThat(canonical(" { \"b\" : [ 1 , 2 ] ,\n\t\"a\" : { \"y\" : true , \"x\" : null } } "))
                .isEqualTo("{\"a\":{\"x\":null,\"y\":true},\"b\":[1,2]}");
        assertThat(canonical("{\"a\":{\"x\":null,\"y\":true},\"b\":[1,2]}"))
                .isEqualTo("{\"a\":{\"x\":null,\"y\":true},\"b\":[1,2]}");
    }

    @Test
    void theOrderOfAnArrayDoesMatter() {
        assertThat(canonical("[1,2]")).isNotEqualTo(canonical("[2,1]"));
    }

    @Test
    void theSameStringInAnyEscapeIsTheSameText() {
        assertThat(canonical("\"\\u00e9\"")).isEqualTo(canonical("\"\u00e9\""));
        assertThat(canonical("\"\\ud83d\\ude00\"")).isEqualTo(canonical("\"\uD83D\uDE00\""));
        assertThat(canonical("\"\\/\"")).isEqualTo("\"/\"");
        assertThat(canonical("\"\\u0041\\u0022\"")).isEqualTo("\"A\\\"\"");
    }

    @Test
    void controlCharactersAreEscapedInLowerCaseHexadecimal() {
        assertThat(canonical("\"\\u0000\\u001F\\u0008\\u000c\\u000A\\u000D\\u0009\""))
                .isEqualTo("\"\\u0000\\u001f\\b\\f\\n\\r\\t\"");
    }

    @Test
    void charactersThatNeedNoEscapeStayLiteral() {
        assertThat(canonical("\"\\u007f\\u2028\\u2029\\u00e9\"")).isEqualTo("\"\u007f\u2028\u2029\u00e9\"");
    }

    @Test
    void anUnpairedSurrogateIsEscapedInsteadOfBecomingGarbage() {
        assertThat(canonical("\"\\ud800\"")).isEqualTo("\"\\ud800\"");
        assertThat(canonical("\"\\ude00\\ud83d\"")).isEqualTo("\"\\ude00\\ud83d\"");
    }

    @Test
    void theSameNumberInAnyFormIsTheSameText() {
        assertThat(canonical("[1, 1.0, 1e0, 1E+0, 10e-1, 0.1e1]")).isEqualTo("[1,1,1,1,1,1]");
        assertThat(canonical("[100, 1e2, 1.0e+2, 100.000]")).isEqualTo("[100,100,100,100]");
        assertThat(canonical("[-0, 0, 0.0, -0.0, 0e5]")).isEqualTo("[0,0,0,0,0]");
        assertThat(canonical("[4.50, 2e-3, 1E30]")).isEqualTo("[4.5,0.002,1e+30]");
    }

    @Test
    void twoDifferentNumbersBeyondADoubleStayDifferent() {
        assertThat(canonical("{\"id\":9007199254740993}")).isNotEqualTo(canonical("{\"id\":9007199254740992}"));
        assertThat(canonical("{\"id\":12345678901234567890}")).isNotEqualTo(canonical("{\"id\":12345678901234567891}"));
    }

    @Test
    void aNumberWithADecimalPartIsADoubleAsInRfc8785() {
        // Una serialización con más dígitos de los que el double guarda, como la de %.17g, es el mismo número.
        assertThat(canonical("[333333333.33333329, 0.10000000000000001, 1.00000000000000001]"))
                .isEqualTo("[333333333.3333333,0.1,1]");
    }

    @Test
    void theLastOfARepeatedKeyWins() {
        assertThat(canonical("{\"a\":1,\"a\":2}")).isEqualTo("{\"a\":2}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "false", "0", "\"text\"", "[]", "{}", "[[[]]]", "{\"a\":{}}"})
    void anyJsonValueCanBeTheBody(String json) {
        assertThat(canonicalize(json)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "{\"a\":}",
                "{\"a\":1,}",
                "[1,]",
                "[1 2]",
                "{'a':1}",
                "{a:1}",
                "{\"a\":1} {\"b\":2}",
                "[1]x",
                "NaN",
                "Infinity",
                "01",
                "1.",
                ".5",
                "+1",
                "0x10",
                "/* comment */ {}",
                "{\"a\":1 // comment\n}",
                "\"unterminated",
                "{\"a\":\"tab\there\"}",
                "not json",
                "a=1&b=2"
            })
    void aBodyThatIsNotOneJsonValueIsRefused(String text) {
        assertThat(canonicalize(text)).isEmpty();
    }

    @Test
    void invalidBytesAreRefused() {
        assertThat(JsonCanonicalizer.canonicalize(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01}))
                .isEmpty();
        assertThat(JsonCanonicalizer.canonicalize(new byte[] {'"', (byte) 0xC3, '"'}))
                .isEmpty();
    }

    @Test
    void aBodyNestedBeyondTheLimitIsRefusedInsteadOfBlowingTheStack() {
        String deep = "[".repeat(5_000) + "]".repeat(5_000);

        assertThat(canonicalize(deep)).isEmpty();
        assertThat(canonicalize("[".repeat(500) + "]".repeat(500))).isPresent();
    }

    @Test
    void theOutputIsItselfCanonical() {
        String canonical = canonical("{\"z\":[1.50,{\"b\":\"\\u00e9\",\"a\":1e2}],\"a\":\"x\"}");

        assertThat(canonical(canonical)).isEqualTo(canonical);
    }

    private static String canonical(String json) {
        return canonicalize(json).orElseThrow(() -> new AssertionError("Not canonicalized: " + json));
    }

    private static String canonical(byte[] json) {
        return JsonCanonicalizer.canonicalize(json).orElseThrow();
    }

    private static Optional<String> canonicalize(String json) {
        return JsonCanonicalizer.canonicalize(json.getBytes(StandardCharsets.UTF_8));
    }
}
