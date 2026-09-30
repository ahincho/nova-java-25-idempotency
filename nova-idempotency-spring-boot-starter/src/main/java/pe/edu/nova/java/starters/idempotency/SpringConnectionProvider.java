package pe.edu.nova.java.starters.idempotency;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import pe.edu.nova.java.libs.idempotency.jdbc.ConnectionProvider;

/**
 * El {@link ConnectionProvider} que suma el almacén JDBC a la transacción de Spring: entrega la conexión de la
 * transacción si hay una abierta en el hilo, y una del pool, con el auto-commit activo, si no.
 *
 * <p>Es el reparto que describe {@code ConnectionProvider}: el lock se toma antes de abrir la transacción y
 * queda a la vista; la respuesta se guarda dentro de ella, en el mismo commit que el cambio del negocio; y el
 * latido corre en otro hilo, sin transacción.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    /**
     * Crea el proveedor.
     *
     * @param dataSource el {@code DataSource} del servicio, el mismo que usa su transacción
     */
    public SpringConnectionProvider(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Connection acquire() throws SQLException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        if (!DataSourceUtils.isConnectionTransactional(connection, dataSource) && !connection.getAutoCommit()) {
            // Fuera de una transacción, una conexión sin auto-commit nunca confirmaría el registro.
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new SQLException("The DataSource returned a connection with auto-commit off", "55000");
        }
        return connection;
    }

    @Override
    public void release(Connection connection) {
        // No cierra la conexión de la transacción: la devuelve Spring al terminarla.
        DataSourceUtils.releaseConnection(connection, dataSource);
    }
}
