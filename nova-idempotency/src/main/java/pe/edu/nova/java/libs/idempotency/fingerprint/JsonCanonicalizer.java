package pe.edu.nova.java.libs.idempotency.fingerprint;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reescribe un texto JSON en la forma canónica de RFC 8785 (JCS): sin espacios, con las claves de cada objeto
 * ordenadas por unidades de código UTF-16, con las cadenas escritas con el mínimo de escapes y con los números
 * como los escribe ECMAScript.
 *
 * <p>Dos textos que dicen lo mismo, aunque los haya reserializado otro cliente, dan el mismo resultado.
 * Si una clave se repite dentro de un objeto gana la última, que es lo que ve el servicio al leerlo.
 *
 * <p>Es la única clase del módulo que lee JSON, y lo hace con el parser de streaming de Jackson 2, que
 * convive con el Jackson 3 de Spring Boot 4. El parser es estricto: no acepta comentarios, comillas simples ni
 * comas de más. Sus límites por defecto, de 1000 niveles de anidamiento y 20 millones de caracteres por
 * cadena, cortan un cuerpo hecho para agotar la pila.
 */
final class JsonCanonicalizer {

    private static final JsonFactory JSON = JsonFactory.builder().build();

    /** Un entero con hasta 15 dígitos cabe exacto en un double, así que se escribe tal cual. */
    private static final int MAX_INLINE_INTEGER_LENGTH = 15;

    private static final int FIRST_PRINTABLE = 0x20;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonCanonicalizer() {}

    /**
     * Canoniza un cuerpo JSON.
     *
     * @param json el cuerpo, en UTF-8, UTF-16 o UTF-32, como lo detecta el parser
     * @return la forma canónica, o vacío si el cuerpo no es un único valor JSON válido. Los errores del
     *         parser se descartan porque su mensaje cita el texto que no pudo leer, y ese texto es el cuerpo
     */
    static Optional<String> canonicalize(byte[] json) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() == null) {
                return Optional.empty();
            }
            StringBuilder canonical = new StringBuilder(json.length);
            write(parser, canonical);
            if (parser.nextToken() != null) {
                return Optional.empty();
            }
            return Optional.of(canonical.toString());
        } catch (IOException | RuntimeException notJson) {
            return Optional.empty();
        }
    }

    private static void write(JsonParser parser, StringBuilder out) throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> writeObject(parser, out);
            case START_ARRAY -> writeArray(parser, out);
            case VALUE_STRING -> quote(parser.getText(), out);
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> out.append(number(parser));
            case VALUE_TRUE -> out.append("true");
            case VALUE_FALSE -> out.append("false");
            case VALUE_NULL -> out.append("null");
            default -> throw new IOException("Unexpected token " + token);
        }
    }

    private static void writeObject(JsonParser parser, StringBuilder out) throws IOException {
        // String.compareTo ordena por unidades de código UTF-16, que es el orden que pide RFC 8785.
        Map<String, String> members = new TreeMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            StringBuilder value = new StringBuilder();
            write(parser, value);
            members.put(name, value.toString());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> member : members.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(member.getKey(), out);
            out.append(':').append(member.getValue());
        }
        out.append('}');
    }

    private static void writeArray(JsonParser parser, StringBuilder out) throws IOException {
        out.append('[');
        boolean first = true;
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_ARRAY) {
            if (token == null) {
                throw new IOException("Unexpected end of input");
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            write(parser, out);
            token = parser.nextToken();
        }
        out.append(']');
    }

    private static String number(JsonParser parser) throws IOException {
        String text = parser.getText();
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            if (text.length() <= MAX_INLINE_INTEGER_LENGTH) {
                return Long.toString(Long.parseLong(text));
            }
            return EcmaNumber.integer(new BigDecimal(text));
        }
        return EcmaNumber.decimal(text);
    }

    /**
     * Escribe una cadena como lo hace {@code JSON.stringify}: solo se escapan la comilla, la barra invertida
     * y los caracteres de control, con las formas cortas donde existen y con cuatro dígitos hexadecimales en
     * minúscula donde no. Los demás caracteres van tal cual, y una sustituta suelta, que no es Unicode
     * válido, se escapa.
     */
    private static void quote(String text, StringBuilder out) {
        out.append('"');
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < FIRST_PRINTABLE) {
                        escape(c, out);
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append(c).append(text.charAt(i + 1));
                        i++;
                    } else if (Character.isSurrogate(c)) {
                        escape(c, out);
                    } else {
                        out.append(c);
                    }
                }
            }
            i++;
        }
        out.append('"');
    }

    private static void escape(char c, StringBuilder out) {
        out.append("\\u")
                .append(HEX[(c >> 12) & 0xF])
                .append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
    }
}
