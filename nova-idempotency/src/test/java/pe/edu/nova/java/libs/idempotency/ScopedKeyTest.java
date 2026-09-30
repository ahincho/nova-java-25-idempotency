package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScopedKeyTest {

    @Test
    void keepsTheScopeAndTheKeyExactly() {
        ScopedKey key = new ScopedKey("customer-42", "\"abc\"");

        assertThat(key.scope()).isEqualTo("customer-42");
        assertThat(key.key()).as("the quotes are part of the key").isEqualTo("\"abc\"");
    }

    @Test
    void keysAreComparedExactly() {
        assertThat(new ScopedKey("s", "k")).isEqualTo(new ScopedKey("s", "k"));
        assertThat(new ScopedKey("s", "k")).isNotEqualTo(new ScopedKey("s", "K"));
        assertThat(new ScopedKey("s", "k")).isNotEqualTo(new ScopedKey("s", "k "));
        assertThat(new ScopedKey("s", "\"k\"")).isNotEqualTo(new ScopedKey("s", "k"));
        assertThat(new ScopedKey("s", "k")).isNotEqualTo(new ScopedKey("S", "k"));
        assertThat(new ScopedKey("a:b", "c")).isNotEqualTo(new ScopedKey("a", "b:c"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void aBlankScopeIsRejected(String scope) {
        assertThatThrownBy(() -> new ScopedKey(scope, "k"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never global");
    }

    @Test
    void aScopeThatCannotBeStoredIsRejected() {
        assertThat(new ScopedKey("s".repeat(255), "k")).isNotNull();
        assertThatThrownBy(() -> new ScopedKey("s".repeat(256), "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedKey("a\u0000b", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedKey("a\nb", "k")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvalidKeyIsRejectedWithoutQuotingIt() {
        assertThatThrownBy(() -> new ScopedKey("s", "secret-é"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> new ScopedKey("s", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedKey("s", "k".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPartsAreRejected() {
        assertThatThrownBy(() -> new ScopedKey(null, "k")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedKey("s", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theStringFormShowsNeitherPart() {
        ScopedKey key = new ScopedKey("customer-4242", "secret-key-7777");

        assertThat(key.toString()).doesNotContain("4242").doesNotContain("7777");
    }

    @Test
    void anOwnerTokenIsFreshEveryTimeAndNeverPrinted() {
        OwnerToken first = OwnerToken.generate();
        OwnerToken second = OwnerToken.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(first.value()).hasSizeBetween(1, 64);
        assertThat(first.toString()).doesNotContain(first.value());
        assertThatThrownBy(() -> new OwnerToken("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnerToken("t".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
