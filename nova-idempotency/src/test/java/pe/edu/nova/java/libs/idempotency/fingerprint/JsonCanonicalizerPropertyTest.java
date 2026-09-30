package pe.edu.nova.java.libs.idempotency.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * La propiedad que motiva la canonización: un cliente que reserializa el mismo JSON, con otro orden de claves,
 * otros espacios, otros escapes u otra forma de escribir un número, produce la misma forma canónica.
 */
class JsonCanonicalizerPropertyTest {

    /** El {@code null} de JSON, que un mapa o una lista de Java no puede distinguir de "no hay". */
    private static final Object JSON_NULL = new Object();

    @Property(tries = 400)
    void reserializingNeverChangesTheCanonicalForm(
            @ForAll("jsonValues") Object value, @ForAll long seedA, @ForAll long seedB) {
        Optional<String> first = canonicalize(render(value, new Random(seedA)));
        Optional<String> second = canonicalize(render(value, new Random(seedB)));

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
    }

    @Property(tries = 400)
    void theCanonicalFormIsItsOwnCanonicalForm(@ForAll("jsonValues") Object value, @ForAll long seed) {
        Optional<String> canonical = canonicalize(render(value, new Random(seed)));

        assertThat(canonical).isPresent();
        assertThat(canonicalize(canonical.get())).isEqualTo(canonical);
    }

    @Provide
    Arbitrary<Object> jsonValues() {
        Arbitrary<String> text = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('"', '\\', '/', ' ', '\n', '\t', '\u0001')
                .withCharRange('À', 'ÿ')
                .withCharRange('一', '丐')
                .ofMaxLength(10);
        Arbitrary<String> withEmoji = Arbitraries.oneOf(text, text.map(value -> value + "😀"));
        Arbitrary<Object> scalars = Arbitraries.oneOf(
                withEmoji.map(value -> (Object) value),
                Arbitraries.longs()
                        .between(-1_000_000_000_000L, 1_000_000_000_000L)
                        .map(value -> (Object) value),
                Arbitraries.doubles().between(-1e6, 1e6).map(value -> (Object) BigDecimal.valueOf(value)),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(value -> (Object) value),
                Arbitraries.just(JSON_NULL));
        return Arbitraries.recursive(
                () -> scalars,
                inner -> Arbitraries.oneOf(
                        inner.list().ofMaxSize(4).map(value -> (Object) value),
                        Arbitraries.maps(withEmoji, inner).ofMaxSize(4).map(value -> (Object) value)),
                0,
                3);
    }

    private static Optional<String> canonicalize(String json) {
        return JsonCanonicalizer.canonicalize(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Escribe el valor como JSON, eligiendo al azar el orden de las claves, los espacios, los escapes y la forma de los números. */
    private static String render(Object value, Random random) {
        StringBuilder out = new StringBuilder();
        render(value, random, out);
        return out.toString();
    }

    private static void render(Object value, Random random, StringBuilder out) {
        switch (value) {
            case Map<?, ?> map -> {
                List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
                Collections.shuffle(entries, random);
                out.append('{').append(space(random));
                boolean first = true;
                for (Map.Entry<?, ?> entry : entries) {
                    if (!first) {
                        out.append(space(random)).append(',').append(space(random));
                    }
                    first = false;
                    quote((String) entry.getKey(), random, out);
                    out.append(space(random)).append(':').append(space(random));
                    render(entry.getValue(), random, out);
                }
                out.append(space(random)).append('}');
            }
            case List<?> list -> {
                out.append('[').append(space(random));
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(space(random)).append(',').append(space(random));
                    }
                    first = false;
                    render(item, random, out);
                }
                out.append(space(random)).append(']');
            }
            case String text -> quote(text, random, out);
            case Long number -> out.append(integer(number, random));
            case BigDecimal number -> out.append(decimal(number, random));
            case Boolean bool -> out.append(bool);
            default -> out.append("null");
        }
    }

    private static String space(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "";
            case 1 -> " ";
            case 2 -> "\n  ";
            default -> "\t";
        };
    }

    private static void quote(String text, Random random, StringBuilder out) {
        out.append('"');
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '"' || codePoint == '\\') {
                out.append('\\').appendCodePoint(codePoint);
            } else if (codePoint < 0x20 || random.nextInt(3) == 0) {
                escape(codePoint, random, out);
            } else if (codePoint == '/' && random.nextBoolean()) {
                out.append("\\/");
            } else {
                out.appendCodePoint(codePoint);
            }
        });
        out.append('"');
    }

    /** Escapa un carácter; uno fuera del plano básico va como sus dos unidades UTF-16, que es como lo escribe JSON. */
    private static void escape(int codePoint, Random random, StringBuilder out) {
        for (char unit : Character.toChars(codePoint)) {
            out.append(String.format(random.nextBoolean() ? "\\u%04x" : "\\u%04X", (int) unit));
        }
    }

    private static String integer(long number, Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Long.toString(number);
            case 1 -> number + ".0";
            case 2 -> number + "e0";
            default -> new BigDecimal(number).toString() + (random.nextBoolean() ? ".000" : "");
        };
    }

    private static String decimal(BigDecimal number, Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> number.toPlainString();
            case 1 -> number.toString();
            case 2 -> number.stripTrailingZeros().toString();
            default -> number.toPlainString() + (number.scale() > 0 ? "0" : ".0");
        };
    }
}
