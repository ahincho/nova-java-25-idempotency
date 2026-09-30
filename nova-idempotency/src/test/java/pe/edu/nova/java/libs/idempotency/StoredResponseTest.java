package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredResponseTest {

    @Test
    void copiesTheBodyOnTheWayInAndOut() {
        byte[] body = {1, 2, 3};
        StoredResponse response = StoredResponse.of(200, body);

        body[0] = 9;
        assertThat(response.body()).containsExactly(1, 2, 3);

        response.body()[1] = 9;
        assertThat(response.body()).containsExactly(1, 2, 3);
        assertThat(response.bodyLength()).isEqualTo(3);
    }

    @Test
    void copiesTheHeadersAndLowerCasesTheirNames() {
        List<String> values = new ArrayList<>(List.of("es", "en"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Language", values);
        headers.put("ETag", List.of("\"v1\""));

        StoredResponse response = new StoredResponse(200, headers, new byte[0]);
        values.add("fr");
        headers.put("X-Late", List.of("late"));

        assertThat(response.headers()).containsOnlyKeys("content-language", "etag");
        assertThat(response.headers().get("content-language")).containsExactly("es", "en");
        assertThatThrownBy(() -> response.headers().put("x", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.headers().get("etag").add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mergesHeadersThatDifferOnlyInCaseAndDropsTheOnesWithoutValues() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Thing", List.of("a"));
        headers.put("set-thing", List.of("b"));
        headers.put("Empty", List.of());

        StoredResponse response = new StoredResponse(200, headers, new byte[0]);

        assertThat(response.headers()).containsOnlyKeys("set-thing");
        assertThat(response.headers().get("set-thing")).containsExactly("a", "b");
    }

    @Test
    void twoResponsesWithTheSameContentAreEqual() {
        StoredResponse first = new StoredResponse(201, Map.of("Location", List.of("/o/1")), new byte[] {1, 2});
        StoredResponse second = new StoredResponse(201, Map.of("location", List.of("/o/1")), new byte[] {1, 2});

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(new StoredResponse(201, Map.of("location", List.of("/o/1")), new byte[] {1, 3}));
        assertThat(first).isNotEqualTo(new StoredResponse(200, Map.of("location", List.of("/o/1")), new byte[] {1, 2}));
        assertThat(first).isNotEqualTo(StoredResponse.of(201, new byte[] {1, 2}));
    }

    @Test
    void rejectsAStatusOutsideHttp() {
        assertThatThrownBy(() -> StoredResponse.of(99, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoredResponse.of(600, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThat(StoredResponse.of(100, new byte[0]).status()).isEqualTo(100);
        assertThat(StoredResponse.of(599, new byte[0]).status()).isEqualTo(599);
    }

    @Test
    void rejectsNullsAndEmptyHeaderNames() {
        assertThatThrownBy(() -> new StoredResponse(200, null, new byte[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoredResponse(200, Map.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoredResponse(200, Map.of(" ", List.of("v")), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStringFormNeverShowsTheBodyOrTheHeaderValues() {
        StoredResponse response = new StoredResponse(
                201,
                Map.of("location", List.of("/orders/secret-header-value")),
                "card-4111-secret-body".getBytes(StandardCharsets.UTF_8));

        assertThat(response.toString())
                .contains("201", "location", "21")
                .doesNotContain("secret-body")
                .doesNotContain("secret-header-value")
                .doesNotContain("4111");
    }
}
