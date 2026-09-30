package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencySettingsTest {

    @Test
    void theDefaultsAreTheOnesOfAdr047() {
        IdempotencySettings defaults = IdempotencySettings.defaults();

        assertThat(defaults.retention()).as("the response is kept 24 hours").isEqualTo(Duration.ofHours(24));
        assertThat(defaults.lockTtl()).as("the lock lasts 60 seconds").isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.lockRenewal()).as("and is renewed every 20").isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.storeTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.purgeBatchSize()).isEqualTo(1000);
        assertThat(defaults.extraReplayHeaders()).isEmpty();
        assertThat(defaults.replayHeaders())
                .containsExactlyInAnyOrder(
                        "location", "content-type", "content-language", "content-location", "etag", "last-modified");
    }

    @Test
    void everythingIsConfigurable() {
        IdempotencySettings settings = IdempotencySettings.defaults()
                .withRetention(Duration.ofHours(1))
                .withLockTtl(Duration.ofSeconds(30))
                .withRetryAfter(Duration.ofSeconds(2))
                .withStoreTimeout(Duration.ofSeconds(1))
                .withPurgeBatchSize(50)
                .withReplayHeaders(List.of("X-Order-Version"));

        assertThat(settings.retention()).isEqualTo(Duration.ofHours(1));
        assertThat(settings.lockTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.storeTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.purgeBatchSize()).isEqualTo(50);
        assertThat(settings.replayHeaders())
                .contains("x-order-version", "location")
                .hasSize(7);
    }

    @Test
    void theRenewalFollowsTheLockUnlessItIsSetAfterwards() {
        assertThat(IdempotencySettings.defaults()
                        .withLockTtl(Duration.ofSeconds(30))
                        .lockRenewal())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(IdempotencySettings.defaults()
                        .withLockTtl(Duration.ofSeconds(30))
                        .withLockRenewal(Duration.ofSeconds(5))
                        .lockRenewal())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aZeroRenewalTurnsTheHeartbeatOff() {
        assertThat(IdempotencySettings.defaults().withLockRenewal(Duration.ZERO).lockRenewal())
                .isZero();
    }

    @Test
    void theRenewalMustBeShorterThanTheLock() {
        assertThatThrownBy(() -> IdempotencySettings.defaults().withLockRenewal(Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencySettings.defaults().withLockRenewal(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationsMustBePositive() {
        IdempotencySettings defaults = IdempotencySettings.defaults();

        assertThatThrownBy(() -> defaults.withRetention(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withLockTtl(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withRetryAfter(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withStoreTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withPurgeBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Set-Cookie",
                "cookie",
                "Authorization",
                "Proxy-Authorization",
                "WWW-Authenticate",
                "Access-Control-Allow-Origin",
                "access-control-expose-headers",
                "Content-Length",
                "Transfer-Encoding",
                "Connection",
                "Date"
            })
    void cookiesCredentialsCorsAndFramingHeadersAreNeverReplayed(String header) {
        assertThatThrownBy(() -> IdempotencySettings.defaults().withReplayHeaders(Set.of(header)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never replayed");
    }

    @Test
    void aReplayHeaderMustBeAValidHeaderName() {
        assertThatThrownBy(() -> IdempotencySettings.defaults().withReplayHeaders(List.of("bad header")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencySettings.defaults().withReplayHeaders(List.of("")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
