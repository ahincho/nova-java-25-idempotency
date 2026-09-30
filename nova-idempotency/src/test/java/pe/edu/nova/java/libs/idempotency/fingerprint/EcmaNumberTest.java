package pe.edu.nova.java.libs.idempotency.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EcmaNumberTest {

    /**
     * Los números de RFC 8785, apéndice B, más otros comunes. El texto esperado es el de {@code String(x)} de
     * ECMAScript, calculado con Node.
     */
    @ParameterizedTest
    @CsvSource({
        "0000000000000000, 0",
        "8000000000000000, 0",
        "0000000000000001, 5e-324",
        "8000000000000001, -5e-324",
        "7fefffffffffffff, 1.7976931348623157e+308",
        "ffefffffffffffff, -1.7976931348623157e+308",
        "4340000000000000, 9007199254740992",
        "c340000000000000, -9007199254740992",
        "4430000000000000, 295147905179352830000",
        "44b52d02c7e14af5, 9.999999999999997e+22",
        "44b52d02c7e14af6, 1e+23",
        "44b52d02c7e14af7, 1.0000000000000001e+23",
        "444b1ae4d6e2ef4e, 999999999999999700000",
        "444b1ae4d6e2ef4f, 999999999999999900000",
        "444b1ae4d6e2ef50, 1e+21",
        "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
        "3eb0c6f7a0b5ed8d, 0.000001",
        "41b3de4355555553, 333333333.3333332",
        "41b3de4355555554, 333333333.33333325",
        "41b3de4355555555, 333333333.3333333",
        "41b3de4355555556, 333333333.3333334",
        "41b3de4355555557, 333333333.33333343",
        "becbf647612f3696, -0.0000033333333333333333",
        "43143ff3c1cb0959, 1424953923781206.2",
        "3ff0000000000000, 1",
        "4024000000000000, 10",
        "40f86a0000000000, 100000",
        "412e848000000000, 1000000",
        "3fb999999999999a, 0.1",
        "3f50624dd2f1a9fc, 0.001",
        "4415af1d78b58c40, 100000000000000000000",
    })
    void writesADoubleLikeEcmaScript(String bits, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));

        assertThat(EcmaNumber.toString(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1.0, 1",
        "1e2, 100",
        "1E+2, 100",
        "100.000, 100",
        "-0.0, 0",
        "0.0, 0",
        "0e10, 0",
        "0.1, 0.1",
        "19.99, 19.99",
        "0.30000000000000004, 0.30000000000000004",
        "0.000001, 0.000001",
        "0.0000001, 1e-7",
        "1e21, 1e+21",
        "-2.50, -2.5",
        "1.7976931348623157e308, 1.7976931348623157e+308",
        "5e-324, 5e-324",
        "333333333.33333329, 333333333.3333333",
        "1.00000000000000001, 1",
        "0.10000000000000000555, 0.1",
        "1e-400, 0",
    })
    void numbersWithADecimalPartAreWrittenLikeRfc8785(String written, String expected) {
        assertThat(EcmaNumber.decimal(written)).isEqualTo(expected);
    }

    @Test
    void aNumberBeyondWhatADoubleCanWriteKeepsItsExactValue() {
        assertThat(EcmaNumber.decimal("1e400")).isEqualTo("1E+400");
        assertThat(EcmaNumber.decimal("-1e400")).isEqualTo("-1E+400");
        assertThat(EcmaNumber.decimal("1e400")).isNotEqualTo(EcmaNumber.decimal("2e400"));
    }

    @Test
    void anIntegerTheDoubleHoldsExactlyIsWrittenLikeRfc8785() {
        assertThat(EcmaNumber.integer(new BigDecimal("9007199254740992"))).isEqualTo("9007199254740992");
        assertThat(EcmaNumber.integer(new BigDecimal("1000000000000000000000"))).isEqualTo("1e+21");
        assertThat(EcmaNumber.integer(new BigDecimal("295147905179352825856")))
                .as("2^68, the same double as the RFC example")
                .isEqualTo("295147905179352830000");
    }

    @Test
    void anIntegerTheDoubleCannotHoldKeepsItsExactValueAndStaysDistinct() {
        String next = EcmaNumber.integer(new BigDecimal("9007199254740993"));
        String previous = EcmaNumber.integer(new BigDecimal("9007199254740992"));

        assertThat(next)
                .as("2^53 + 1 is not 2^53")
                .isEqualTo("9007199254740993")
                .isNotEqualTo(previous);
        assertThat(EcmaNumber.integer(new BigDecimal("123456789012345678"))).isEqualTo("123456789012345678");
        assertThat(EcmaNumber.integer(new BigDecimal("123456789012345679"))).isEqualTo("123456789012345679");
    }

    @Test
    void theExactFormOfAnIntegerKeepsItsValue() {
        BigDecimal integer = new BigDecimal("12345678901234567890");

        String exact = EcmaNumber.integer(integer);

        assertThat(new BigDecimal(exact)).isEqualByComparingTo(integer);
        assertThat(exact).isNotEqualTo(EcmaNumber.integer(new BigDecimal("12345678901234567891")));
    }
}
