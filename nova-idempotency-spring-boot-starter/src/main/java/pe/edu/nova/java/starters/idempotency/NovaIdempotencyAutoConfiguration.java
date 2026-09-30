package pe.edu.nova.java.starters.idempotency;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pe.edu.nova.java.libs.idempotency.Fingerprinter;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.ScopeResolver;
import pe.edu.nova.java.libs.idempotency.fingerprint.Sha256Fingerprinter;
import pe.edu.nova.java.libs.idempotency.jdbc.JdbcIdempotencyStore;
import pe.edu.nova.java.libs.idempotency.memory.InMemoryIdempotencyStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conecta la capacidad de idempotencia con Spring MVC (ADR-047).
 *
 * <p>Cada pieza es un bean que el servicio reemplaza declarando el suyo: el almacén, el alcance, la huella y la
 * forma de los errores. Las reglas de ADR-047 las aplica el núcleo, y el starter se niega a arrancar en dos
 * casos: sin un almacén persistente, salvo que se elija la memoria con {@code nova.idempotency.store=memory}, y
 * sin una forma de saber de quién es la clave.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@ConditionalOnProperty(prefix = "nova.idempotency", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(NovaIdempotencyProperties.class)
public class NovaIdempotencyAutoConfiguration {

    /** Dónde va el filtro: tarde, después de la seguridad y de los filtros de trazas, cerca de Spring MVC. */
    public static final int FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    static final String NO_STORE = "Nova idempotency has no persistent store. Configure a DataSource for the "
            + "JDBC store, declare an IdempotencyStore bean, or choose the memory store on purpose with "
            + "nova.idempotency.store=memory, which loses its records on restart and is not shared by replicas.";

    static final String NO_SCOPE = "Nova idempotency cannot tell whose key a request carries, and a key is never "
            + "global. Set nova.idempotency.scope-header, such as X-Customer-Id, add Spring Security so the "
            + "authenticated identity is the scope, or declare a ScopeResolver bean.";

    private static final String SPRING_SECURITY = "org.springframework.security.core.context.SecurityContextHolder";
    private static final String SPRING_TX = "org.springframework.transaction.PlatformTransactionManager";

    /** Crea la auto-configuración; la instancia Spring Boot. */
    public NovaIdempotencyAutoConfiguration() {}

    /**
     * El motor de la idempotencia, con la configuración de {@code nova.idempotency.*}.
     *
     * @param stores     el almacén
     * @param properties la configuración
     * @return el motor
     * @throws IllegalStateException si no hay almacén, o si no es persistente y no se eligió a propósito
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public IdempotencyEngine novaIdempotencyEngine(
            ObjectProvider<IdempotencyStore> stores, NovaIdempotencyProperties properties) {
        IdempotencyStore store = stores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(NO_STORE);
        }
        if (!store.persistent() && properties.getStore() != NovaIdempotencyProperties.Store.MEMORY) {
            throw new IllegalStateException(NO_STORE);
        }
        return new IdempotencyEngine(store, properties.toSettings());
    }

    /**
     * De quién es la clave: el header de {@code nova.idempotency.scope-header}, o la identidad autenticada si el
     * servicio usa Spring Security.
     *
     * @param properties la configuración
     * @param context    el contexto, para ver si está Spring Security
     * @return el resolvedor
     * @throws IllegalStateException si no hay header ni Spring Security
     */
    @Bean
    @ConditionalOnMissingBean
    public ScopeResolver novaIdempotencyScopeResolver(
            NovaIdempotencyProperties properties, ApplicationContext context) {
        String header = properties.getScopeHeader();
        if (header != null && !header.isBlank()) {
            return ScopeResolver.ofHeader(header.strip());
        }
        if (ClassUtils.isPresent(SPRING_SECURITY, context.getClassLoader())) {
            return ScopeResolver.ofPrincipal();
        }
        throw new IllegalStateException(NO_SCOPE);
    }

    /**
     * La huella de Nova: SHA-256 del alcance, el método, la ruta y el cuerpo JSON canónico.
     *
     * @return la huella
     */
    @Bean
    @ConditionalOnMissingBean
    public Fingerprinter novaIdempotencyFingerprinter() {
        return new Sha256Fingerprinter();
    }

    /**
     * Los errores en el sobre de Nova, con el mapper JSON del servicio.
     *
     * @param mappers el mapper del servicio, si hay uno
     * @return el responder
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyErrorResponder novaIdempotencyErrorResponder(ObjectProvider<JsonMapper> mappers) {
        return new NovaEnvelopeErrorResponder(
                mappers.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    /**
     * El filtro que aplica la idempotencia a las operaciones con {@link Idempotent}.
     *
     * @param engine        el motor
     * @param store         el almacén, para saber si se suma a la transacción
     * @param scopes        el alcance
     * @param fingerprinter la huella
     * @param errors        los errores
     * @param mappings      los mapeos de Spring MVC
     * @param properties    la configuración
     * @param context       el contexto, para buscar el gestor de transacciones
     * @return el registro del filtro
     */
    @Bean
    @ConditionalOnMissingBean(name = "novaIdempotencyFilter")
    FilterRegistrationBean<IdempotencyFilter> novaIdempotencyFilter(
            IdempotencyEngine engine,
            ObjectProvider<IdempotencyStore> store,
            ScopeResolver scopes,
            Fingerprinter fingerprinter,
            IdempotencyErrorResponder errors,
            ObjectProvider<RequestMappingHandlerMapping> mappings,
            NovaIdempotencyProperties properties,
            ApplicationContext context) {
        String header = properties.getScopeHeader();
        IdempotencyError scopeMissing = header != null && !header.isBlank()
                ? IdempotencyError.scopeHeaderMissing(header.strip())
                : IdempotencyError.unauthenticated();
        TransactionalExecution transactions = null;
        if (properties.isSameTransaction()
                && store.getIfAvailable() instanceof JdbcIdempotencyStore
                && ClassUtils.isPresent(SPRING_TX, context.getClassLoader())) {
            transactions = Transactions.execution(context);
        }
        IdempotencyFilter filter = new IdempotencyFilter(
                new IdempotentOperations(mappings),
                engine,
                scopes,
                fingerprinter,
                errors,
                scopeMissing,
                properties.getRetryAfter(),
                transactions);
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("novaIdempotencyFilter");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    /**
     * La purga programada de los registros vencidos. Se apaga con {@code nova.idempotency.purge-interval=0}.
     *
     * @param engine     el motor
     * @param properties la configuración
     * @return la purga, o una que no hace nada si está apagada
     */
    @Bean
    @ConditionalOnMissingBean
    IdempotencyPurger novaIdempotencyPurger(IdempotencyEngine engine, NovaIdempotencyProperties properties) {
        if (properties.getPurgeInterval().isNegative()) {
            throw new IllegalArgumentException("nova.idempotency.purge-interval must not be negative");
        }
        return new IdempotencyPurger(engine, properties.getPurgeInterval());
    }

    /** El almacén JDBC, con el {@code DataSource} del servicio. Es el de por defecto. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.datasource.DataSourceUtils")
    @ConditionalOnProperty(prefix = "nova.idempotency", name = "store", havingValue = "jdbc", matchIfMissing = true)
    static class JdbcStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        IdempotencyStore novaIdempotencyStore(
                ObjectProvider<DataSource> dataSources, NovaIdempotencyProperties properties) {
            DataSource dataSource = dataSources.getIfUnique();
            if (dataSource == null) {
                throw new IllegalStateException(NO_STORE);
            }
            return JdbcIdempotencyStore.builder(new SpringConnectionProvider(dataSource))
                    .tableName(properties.getTableName())
                    .build();
        }
    }

    /** El almacén en memoria, solo si se eligió a propósito. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "nova.idempotency", name = "store", havingValue = "memory")
    static class MemoryStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        IdempotencyStore novaIdempotencyStore() {
            return new InMemoryIdempotencyStore();
        }
    }

    /** Aparte, para que el starter no cargue {@code spring-tx} si el servicio no lo tiene. */
    private static final class Transactions {

        private Transactions() {}

        static TransactionalExecution execution(ApplicationContext context) {
            org.springframework.transaction.PlatformTransactionManager manager = context.getBeanProvider(
                            org.springframework.transaction.PlatformTransactionManager.class)
                    .getIfUnique();
            return manager == null ? null : new TransactionalExecution(manager);
        }
    }
}
