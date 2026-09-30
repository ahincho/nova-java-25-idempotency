package pe.edu.nova.java.libs.idempotency.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Lo que un servicio con transacciones escribe para sumar el almacén a la transacción del negocio: entrega
 * la conexión de la transacción si hay una abierta en el hilo, y una nueva, con el auto-commit activo, si no.
 *
 * <p>Con Spring sería {@code DataSourceUtils.getConnection(dataSource)} y
 * {@code DataSourceUtils.releaseConnection(connection, dataSource)}. Aquí la "transacción" es una conexión que
 * la prueba abre con el auto-commit apagado y ata al hilo.
 */
final class TransactionalProvider implements ConnectionProvider {

    private final DataSource dataSource;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    TransactionalProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Ata la conexión de una transacción al hilo actual. */
    void bind(Connection connection) {
        transaction.set(connection);
    }

    /** Suelta la transacción del hilo actual. */
    void unbind() {
        transaction.remove();
    }

    @Override
    public Connection acquire() throws SQLException {
        Connection bound = transaction.get();
        return bound != null ? bound : ConnectionProvider.of(dataSource).acquire();
    }

    @Override
    public void release(Connection connection) throws SQLException {
        // La conexión de la transacción no se cierra: es de quien la abrió, que hace commit o rollback.
        if (connection != transaction.get()) {
            connection.close();
        }
    }
}
