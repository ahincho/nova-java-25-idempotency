package pe.edu.nova.java.libs.idempotency.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.testing.MutableClock;

/** La configuración del almacén y lo que hace con un proveedor que falla: no necesita base de datos ni Docker. */
class JdbcIdempotencyStoreConfigurationTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "idempotency_record",
                "orders.idempotency_record",
                "_records",
                "R2D2",
                "a.b",
                "orders_v2.records_1"
            })
    void acceptsAnIdentifierOptionallyPrefixedBySchema(String name) {
        assertThat(JdbcIdempotencyStore.builder(unusedProvider())
                        .tableName(name)
                        .build())
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "a b",
                "a;drop table x",
                "records--",
                "1records",
                "a.b.c",
                "a-b",
                "a.",
                ".a",
                "\"quoted\"",
                "a'b",
                "a(b)",
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            })
    void refusesAnythingThatIsNotASimpleIdentifierBecauseTheNameGoesIntoTheSql(String name) {
        assertThatThrownBy(() -> JdbcIdempotencyStore.builder(unusedProvider()).tableName(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier");
    }

    @Test
    void theDefaultTableIsTheOneOfTheScript() {
        assertThat(JdbcIdempotencyStore.DEFAULT_TABLE).isEqualTo("idempotency_record");
        assertThat(PostgresSupport.schemaScript())
                .contains("create table " + JdbcIdempotencyStore.DEFAULT_TABLE + " (");
    }

    @Test
    void theBuilderRequiresItsPieces() {
        assertThatThrownBy(() -> JdbcIdempotencyStore.builder((DataSource) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JdbcIdempotencyStore.builder((ConnectionProvider) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JdbcIdempotencyStore.builder(unusedProvider()).clock(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JdbcIdempotencyStore.builder(unusedProvider()).tableName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDataSourceWithAutoCommitOffIsRefusedAndItsConnectionIsClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        DataSource offAutoCommit = dataSourceOf(connection(false, closed));

        assertThatThrownBy(() -> ConnectionProvider.of(offAutoCommit).acquire())
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessageContaining("auto-commit")
                .hasMessageContaining("ConnectionProvider");
        assertThat(closed).isTrue();
    }

    @Test
    void aDataSourceWithAutoCommitOnIsUsedAsIs() throws SQLException {
        AtomicBoolean closed = new AtomicBoolean();
        Connection connection = connection(true, closed);
        ConnectionProvider provider = ConnectionProvider.of(dataSourceOf(connection));

        assertThat(provider.acquire()).isSameAs(connection);
        provider.release(connection);

        assertThat(closed).as("by default a released connection is closed").isTrue();
    }

    @Test
    void aDataSourceIsRequired() {
        assertThatThrownBy(() -> ConnectionProvider.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void whenTheProviderFailsTheErrorSaysTheOperationAndTheSqlStateButNotTheDriverMessage() {
        // El mensaje del driver cita la clave, como hace un servidor con la fila de una restricción rota.
        ConnectionProvider failing = () -> {
            throw new SQLException("connection lost while writing key marker-KEY-4f9d2c", "08006");
        };
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(failing).build();
        ScopedKey key = new ScopedKey("scope", "marker-KEY-4f9d2c");

        assertThatThrownBy(() -> store.acquire(key, OwnerToken.generate(), "fp", TTL, TIMEOUT))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessage("The store failed to acquire (SQLState 08006)")
                .hasNoCause();
    }

    @Test
    void aFailureWithoutSqlStateIsReportedAsUnknown() {
        ConnectionProvider failing = () -> {
            throw new SQLException("boom");
        };
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(failing).build();

        assertThatThrownBy(() -> store.release(new ScopedKey("scope", "key"), OwnerToken.generate(), TIMEOUT))
                .hasMessage("The store failed to release (SQLState unknown)");
    }

    @Test
    void theClockIsOptionalAndTheStoreIsPersistent() {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(unusedProvider())
                .clock(new MutableClock())
                .build();

        assertThat(store.persistent()).isTrue();
    }

    private static ConnectionProvider unusedProvider() {
        return () -> {
            throw new SQLException("This provider is only used to build the store");
        };
    }

    private static DataSource dataSourceOf(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcIdempotencyStoreConfigurationTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return connection;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Una conexión falsa que solo sabe decir si tiene el auto-commit activo y si la cerraron. */
    private static Connection connection(boolean autoCommit, AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                JdbcIdempotencyStoreConfigurationTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "close" -> {
                        closed.set(true);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
