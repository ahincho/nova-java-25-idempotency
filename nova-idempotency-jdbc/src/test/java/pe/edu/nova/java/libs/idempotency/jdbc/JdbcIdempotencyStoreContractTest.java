package pe.edu.nova.java.libs.idempotency.jdbc;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.testing.IdempotencyStoreContract;

/**
 * El almacén JDBC cumple el mismo contrato que el de memoria, contra un PostgreSQL 17 real. Se salta si la
 * máquina no tiene Docker.
 */
class JdbcIdempotencyStoreContractTest extends IdempotencyStoreContract {

    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(PostgresSupport.dockerAvailable(), "Docker is not available");
        dataSource = PostgresSupport.dataSource();
    }

    @BeforeEach
    void emptyTheTable() {
        // La purga ve toda la tabla, y cada caso arranca su propio reloj en el mismo instante.
        PostgresSupport.truncate(dataSource);
    }

    @Override
    protected IdempotencyStore createStore(Clock clock) {
        return JdbcIdempotencyStore.builder(dataSource).clock(clock).build();
    }

    @Override
    protected boolean expectedPersistent() {
        return true;
    }
}
