package pe.edu.nova.java.libs.idempotency.fingerprint;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Cómo se escribe un número JSON en la forma canónica de RFC 8785, sección 3.2.2.3: la de
 * {@code Number.prototype.toString} de ECMAScript, con los dígitos más cortos que identifican al
 * {@code double}. Es lo que hace que {@code 1.0}, {@code 1e0} y {@code 1} sean el mismo número, y que
 * {@code 333333333.33333329} se escriba {@code 333333333.3333333}.
 *
 * <p>RFC 8785 trata todo número como un {@code double}, y aquí también, con una excepción deliberada: un
 * <strong>entero</strong> que el {@code double} no representa exacto conserva su valor. Sin eso, dos
 * identificadores que difieren en los últimos dígitos, como {@code 9007199254740992} y
 * {@code 9007199254740993}, darían el mismo texto, y la misma clave con otro identificador se repetiría
 * como si fuera la misma petición. Los números con parte decimal o exponente siguen al RFC sin cambios.
 */
final class EcmaNumber {

    /** Hasta este exponente ECMAScript escribe un número entero sin notación científica. */
    private static final int MAX_PLAIN_EXPONENT = 21;

    /** Desde este exponente negativo ECMAScript pasa a notación científica. */
    private static final int MIN_PLAIN_EXPONENT = -6;

    private EcmaNumber() {}

    /**
     * Los dígitos significativos de un número positivo y dónde cae el punto decimal.
     *
     * @param digits los dígitos, sin ceros a la izquierda ni a la derecha
     * @param point  la posición del punto respecto de los dígitos, que es la {@code n} de la especificación: el
     *               valor es {@code digits × 10^(point − k)}, con {@code k} la cantidad de dígitos
     */
    private record Digits(String digits, int point) {

        BigDecimal exactValue() {
            return new BigDecimal(new BigInteger(digits), digits.length() - point);
        }
    }

    /**
     * Escribe un número con parte decimal o exponente, como {@code 4.50} o {@code 2e-3}.
     *
     * @param text el número tal como lo escribió el cliente, con la sintaxis de JSON
     * @return el texto canónico. Un número que ni un {@code double} alcanza a escribir, como {@code 1e400},
     *         que RFC 8785 no admite, conserva su valor exacto
     */
    static String decimal(String text) {
        double asDouble = Double.parseDouble(text);
        if (Double.isInfinite(asDouble)) {
            return new BigDecimal(text).stripTrailingZeros().toString();
        }
        return toString(asDouble);
    }

    /**
     * Escribe un número entero de más de quince dígitos.
     *
     * @param integer el entero exacto
     * @return el texto canónico si el {@code double} lo representa exacto; si no, su valor exacto, para no
     *         confundirlo con el entero vecino
     */
    static String integer(BigDecimal integer) {
        double asDouble = integer.doubleValue();
        if (Double.isFinite(asDouble) && new BigDecimal(asDouble).compareTo(integer) == 0) {
            return toString(asDouble);
        }
        return integer.stripTrailingZeros().toString();
    }

    /**
     * Escribe un {@code double} como lo hace ECMAScript.
     *
     * @param value un número finito
     * @return el texto, como {@code 1e+21}, {@code 0.000001} o {@code 333333333.3333332}
     */
    static String toString(double value) {
        if (value == 0) {
            return "0";
        }
        return format(value < 0, shortest(Math.abs(value)));
    }

    /**
     * Los dígitos más cortos que identifican a un {@code double} positivo. Si hay varios de esa longitud, el
     * más cercano al valor y, si empatan, el par (ECMAScript, 6.1.6.1.20).
     */
    private static Digits shortest(double positive) {
        Digits fromJdk = parse(Double.toString(positive));
        // Desde el JDK 19, Double.toString da los dígitos más cortos, pero nunca menos de dos: cuando uno
        // basta, elige el decimal de dos dígitos más cercano y no el de un dígito. ECMAScript pide el de uno.
        if (fromJdk.digits().length() == 2) {
            Digits oneDigit = closestOneDigit(positive, fromJdk);
            if (oneDigit != null) {
                return oneDigit;
            }
        }
        return fromJdk;
    }

    private static Digits parse(String jdk) {
        int exponentAt = jdk.indexOf('E');
        int exponent = exponentAt < 0 ? 0 : Integer.parseInt(jdk.substring(exponentAt + 1));
        String mantissa = exponentAt < 0 ? jdk : jdk.substring(0, exponentAt);
        int dot = mantissa.indexOf('.');
        String all = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        int point = (dot < 0 ? mantissa.length() : dot) + exponent;

        int start = 0;
        while (start < all.length() - 1 && all.charAt(start) == '0') {
            start++;
        }
        point -= start;
        int end = all.length();
        while (end > start + 1 && all.charAt(end - 1) == '0') {
            end--;
        }
        return new Digits(all.substring(start, end), point);
    }

    /**
     * El decimal de un dígito que identifica al {@code double}, si hay alguno: el que está justo debajo o
     * justo encima de lo que devolvió el JDK. Si sirven los dos, el más cercano.
     */
    private static Digits closestOneDigit(double positive, Digits jdk) {
        int first = jdk.digits().charAt(0) - '0';
        Digits below = new Digits(Integer.toString(first), jdk.point());
        Digits above =
                first == 9 ? new Digits("1", jdk.point() + 1) : new Digits(Integer.toString(first + 1), jdk.point());
        boolean belowMatches = identifies(below, positive);
        boolean aboveMatches = identifies(above, positive);
        if (belowMatches && aboveMatches) {
            BigDecimal exact = new BigDecimal(positive);
            int closer = exact.subtract(below.exactValue())
                    .compareTo(above.exactValue().subtract(exact));
            if (closer != 0) {
                return closer < 0 ? below : above;
            }
            return first % 2 == 0 ? below : above;
        }
        if (belowMatches) {
            return below;
        }
        return aboveMatches ? above : null;
    }

    private static boolean identifies(Digits oneDigit, double positive) {
        return Double.parseDouble(oneDigit.digits() + "E" + (oneDigit.point() - 1)) == positive;
    }

    private static String format(boolean negative, Digits shortest) {
        String digits = shortest.digits();
        int k = digits.length();
        int n = shortest.point();
        StringBuilder text = new StringBuilder();
        if (negative) {
            text.append('-');
        }
        if (k <= n && n <= MAX_PLAIN_EXPONENT) {
            text.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= MAX_PLAIN_EXPONENT) {
            text.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (MIN_PLAIN_EXPONENT < n && n <= 0) {
            text.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            text.append(digits.charAt(0));
            if (k > 1) {
                text.append('.').append(digits, 1, k);
            }
            text.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return text.toString();
    }
}
