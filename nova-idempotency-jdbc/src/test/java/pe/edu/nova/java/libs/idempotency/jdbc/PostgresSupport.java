package pe.edu.nova.java.libs.idempotency.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Un PostgreSQL 17 real, levantado una vez por JVM con Testcontainers, con la tabla del script ya creada.
 *
 * <p>Cada conexión que entrega el {@code DataSource} es física y nueva, así que las pruebas de concurrencia
 * corren de verdad en paralelo contra la base.
 */
final class PostgresSupport {

    static final String SCHEMA_SCRIPT = "/db/nova/idempotency/postgresql/V1__create_idempotency_record.sql";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("idempotency")
            .withUsername("idempotency")
            .withPassword("idempotency-test");

    private static PGSimpleDataSource dataSource;

    private PostgresSupport() {}

    /**
     * Dice si esta máquina tiene Docker.
     *
     * @return {@code true} si Testcontainers puede levantar contenedores
     */
    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /**
     * El {@code DataSource} de la base de pruebas, que se levanta la primera vez.
     *
     * @return el {@code DataSource}
     */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            PGSimpleDataSource created = new PGSimpleDataSource();
            created.setUrl(POSTGRES.getJdbcUrl());
            created.setUser(POSTGRES.getUsername());
            created.setPassword(POSTGRES.getPassword());
            execute(created, schemaScript());
            dataSource = created;
        }
        return dataSource;
    }

    /**
     * El script de la tabla, tal como viene dentro del módulo.
     *
     * @return el SQL
     */
    static String schemaScript() {
        try (InputStream script = PostgresSupport.class.getResourceAsStream(SCHEMA_SCRIPT)) {
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Corre una sentencia, o varias separadas por punto y coma.
     *
     * @param dataSource la base
     * @param sql        el SQL
     */
    static void execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run the SQL: " + e.getMessage(), e);
        }
    }

    /**
     * Deja vacía la tabla de los registros.
     *
     * @param dataSource la base
     */
    static void truncate(DataSource dataSource) {
        execute(dataSource, "truncate table idempotency_record");
    }
}
