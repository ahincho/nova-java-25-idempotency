package pe.edu.nova.java.starters.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import pe.edu.nova.java.libs.idempotency.Fingerprinter;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;
import pe.edu.nova.java.libs.idempotency.IdempotencySettings;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.ScopeResolver;
import pe.edu.nova.java.libs.idempotency.fingerprint.Sha256Fingerprinter;
import pe.edu.nova.java.libs.idempotency.jdbc.JdbcIdempotencyStore;
import pe.edu.nova.java.libs.idempotency.memory.InMemoryIdempotencyStore;

/** Qué arma la auto-configuración, y en qué casos se niega a arrancar. No necesita un servidor. */
class NovaIdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NovaIdempotencyAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader("org.springframework.security"));

    @Test
    void withTheMemoryStoreAndAScopeHeaderEveryPieceIsThere() {
        runner.withPropertyValues("nova.idempotency.store=memory", "nova.idempotency.scope-header=X-Customer-Id")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(InMemoryIdempotencyStore.class);
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context).getBean(Fingerprinter.class).isInstanceOf(Sha256Fingerprinter.class);
                    assertThat(context)
                            .getBean(IdempotencyErrorResponder.class)
                            .isInstanceOf(NovaEnvelopeErrorResponder.class);
                    assertThat(context).hasBean("novaIdempotencyFilter");
                    assertThat(context.getBean("novaIdempotencyFilter", FilterRegistrationBean.class)
                                    .getOrder())
                            .isEqualTo(NovaIdempotencyAutoConfiguration.FILTER_ORDER);
                });
    }

    @Test
    void withADataSourceTheStoreIsJdbc() {
        runner.withPropertyValues("nova.idempotency.scope-header=X-Customer-Id")
                .withBean(DataSource.class, SimpleDriverDataSource::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(IdempotencyStore.class).isInstanceOf(JdbcIdempotencyStore.class);
                });
    }

    @Test
    void withADataSourceAndATransactionManagerTheFilterJoinsTheTransaction() {
        runner.withPropertyValues("nova.idempotency.scope-header=X-Customer-Id")
                .withBean(DataSource.class, SimpleDriverDataSource::new)
                .withBean(
                        DataSourceTransactionManager.class,
                        () -> new DataSourceTransactionManager(new SimpleDriverDataSource()))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withoutAPersistentStoreTheServiceDoesNotStart() {
        runner.withPropertyValues("nova.idempotency.scope-header=X-Customer-Id").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage(NovaIdempotencyAutoConfiguration.NO_STORE);
        });
    }

    @Test
    void aStoreThatIsNotPersistentMustBeChosenOnPurpose() {
        runner.withPropertyValues("nova.idempotency.scope-header=X-Customer-Id")
                .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessage(NovaIdempotencyAutoConfiguration.NO_STORE);
                });
    }

    @Test
    void withoutAScopeTheServiceDoesNotStartBecauseAKeyIsNeverGlobal() {
        runner.withPropertyValues("nova.idempotency.store=memory").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage(NovaIdempotencyAutoConfiguration.NO_SCOPE);
        });
    }

    @Test
    void withSpringSecurityTheScopeIsTheAuthenticatedIdentity() {
        // Sin el filtro, el reemplazo de SecurityContextHolder de las pruebas queda a la vista.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NovaIdempotencyAutoConfiguration.class))
                .withPropertyValues("nova.idempotency.store=memory")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ScopeResolver.class));
    }

    @Test
    void everyPieceCanBeReplacedByTheService() {
        ScopeResolver scope = request -> Optional.of("tenant");
        Fingerprinter fingerprinter = (s, request) -> "fixed";
        IdempotencyErrorResponder responder = (response, error) -> response.setStatus(error.status());
        runner.withPropertyValues("nova.idempotency.store=memory")
                .withBean(ScopeResolver.class, () -> scope)
                .withBean(Fingerprinter.class, () -> fingerprinter)
                .withBean(IdempotencyErrorResponder.class, () -> responder)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ScopeResolver.class)).isSameAs(scope);
                    assertThat(context.getBean(Fingerprinter.class)).isSameAs(fingerprinter);
                    assertThat(context.getBean(IdempotencyErrorResponder.class)).isSameAs(responder);
                });
    }

    @Test
    void itCanBeTurnedOff() {
        runner.withPropertyValues("nova.idempotency.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdempotencyEngine.class));
    }

    @Test
    void thePropertiesBecomeTheSettingsOfTheCore() {
        NovaIdempotencyProperties properties = new NovaIdempotencyProperties();
        properties.setRetention(Duration.ofHours(48));
        properties.setLockTtl(Duration.ofSeconds(30));
        properties.setRetryAfter(Duration.ofSeconds(2));
        properties.setStoreTimeout(Duration.ofSeconds(3));
        properties.setPurgeBatchSize(50);
        properties.setReplayHeaders(java.util.List.of("X-Request-Id"));

        IdempotencySettings settings = properties.toSettings();

        assertThat(settings.retention()).isEqualTo(Duration.ofHours(48));
        assertThat(settings.lockTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.lockRenewal()).as("a third of the lock").isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.storeTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.purgeBatchSize()).isEqualTo(50);
        assertThat(settings.replayHeaders()).contains("x-request-id", "location");

        properties.setLockRenewal(Duration.ZERO);
        assertThat(properties.toSettings().lockRenewal()).isZero();
    }

    @Test
    void theDefaultsAreThoseOfTheCore() {
        NovaIdempotencyProperties properties = new NovaIdempotencyProperties();

        assertThat(properties.toSettings()).isEqualTo(IdempotencySettings.defaults());
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isSameTransaction()).isTrue();
        assertThat(properties.getStore()).isNull();
        assertThat(properties.getPurgeInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getTableName()).isEqualTo(JdbcIdempotencyStore.DEFAULT_TABLE);
    }

    @Test
    void aNegativePurgeIntervalIsRefused() {
        runner.withPropertyValues(
                        "nova.idempotency.store=memory",
                        "nova.idempotency.scope-header=X-Customer-Id",
                        "nova.idempotency.purge-interval=-1s")
                .run(context -> assertThat(context).hasFailed());
    }
}
