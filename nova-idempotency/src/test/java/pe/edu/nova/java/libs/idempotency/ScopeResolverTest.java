package pe.edu.nova.java.libs.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScopeResolverTest {

    @Test
    void thePrincipalIsTheScopeOfAnAuthenticatedRequest() {
        IdempotentRequest request = IdempotentRequest.of("POST", "/orders", Map.of(), "alice", null);

        assertThat(ScopeResolver.ofPrincipal().resolve(request)).contains("alice");
    }

    @Test
    void anAnonymousRequestHasNoScope() {
        assertThat(ScopeResolver.ofPrincipal().resolve(IdempotentRequest.of("POST", "/orders", null)))
                .isEmpty();
        assertThat(ScopeResolver.ofPrincipal()
                        .resolve(IdempotentRequest.of("POST", "/orders", Map.of(), "   ", new byte[0])))
                .isEmpty();
    }

    @Test
    void aHeaderCanBeTheScopeLikeTheCustomerIdThatTheBffForwards() {
        IdempotentRequest request =
                IdempotentRequest.of("POST", "/orders", Map.of("x-customer-id", " 42 "), null, new byte[0]);

        assertThat(ScopeResolver.ofHeader("X-Customer-Id").resolve(request)).contains("42");
    }

    @Test
    void aMissingOrBlankHeaderHasNoScope() {
        ScopeResolver resolver = ScopeResolver.ofHeader("X-Customer-Id");

        assertThat(resolver.resolve(IdempotentRequest.of("POST", "/orders", null)))
                .isEmpty();
        assertThat(resolver.resolve(IdempotentRequest.of("POST", "/orders", Map.of("X-Customer-Id", " "), null, null)))
                .isEmpty();
    }

    @Test
    void aResolverIsAFunctionSoAnOrganizationCanWriteItsOwn() {
        ScopeResolver tenantAndUser = request ->
                request.header("X-Tenant").flatMap(tenant -> request.principal().map(user -> tenant + "/" + user));

        assertThat(tenantAndUser.resolve(
                        IdempotentRequest.of("POST", "/orders", Map.of("x-tenant", "utp"), "alice", null)))
                .isEqualTo(Optional.of("utp/alice"));
    }

    @Test
    void theRequestViewIsCaseInsensitiveAndImmutable() {
        byte[] body = {1, 2};
        Map<String, String> headers = new HashMap<>(Map.of("Idempotency-Key", "k"));
        IdempotentRequest request = IdempotentRequest.of("POST", "/orders", headers, "alice", body);

        headers.put("Idempotency-Key", "changed");
        body[0] = 9;

        assertThat(request.header("IDEMPOTENCY-KEY")).contains("k");
        assertThat(request.header("missing")).isEmpty();
        assertThat(request.body()).containsExactly(1, 2);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/orders");
    }

    @Test
    void aNameIsRequired() {
        assertThatThrownBy(() -> ScopeResolver.ofHeader(null)).isInstanceOf(NullPointerException.class);
    }
}
