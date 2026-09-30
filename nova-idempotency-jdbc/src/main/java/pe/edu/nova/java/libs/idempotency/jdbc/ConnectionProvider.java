package pe.edu.nova.java.libs.idempotency.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;

/**
 * De dónde saca el almacén la conexión de cada operación: es lo que permite sumarlo a la transacción del
 * negocio.
 *
 * <p>El almacén pide una conexión al empezar cada operación y la devuelve al terminarla. Nunca hace commit ni
 * rollback, ni cambia el modo de auto-commit: eso es de quien la entrega.
 *
 * <ul>
 *   <li><strong>Fuera de una transacción</strong>, la conexión tiene el auto-commit activo, así que cada
 *       sentencia se confirma sola. Es lo que hace {@link #of(DataSource)}.
 *   <li><strong>Dentro de una transacción</strong>, la conexión es la de la transacción del negocio, y sus
 *       sentencias se confirman o se deshacen con ella. Un servicio que usa Spring lo escribe con
 *       {@code DataSourceUtils.getConnection(dataSource)} y {@code DataSourceUtils.releaseConnection}.
 * </ul>
 *
 * <p>Un proveedor que devuelve la conexión de la transacción cuando hay una abierta en el hilo, y una nueva
 * cuando no, da el reparto que conviene sin más: el lock se toma antes de abrir la transacción, así que
 * queda confirmado y visible, y un duplicado recibe un 409 enseguida; la respuesta se guarda dentro de la
 * transacción, y se confirma en el mismo commit que el cambio del negocio, así que una caída entre los dos ya
 * no vuelve a ejecutar la operación; y el latido que renueva el lock corre en otro hilo, sin transacción, y
 * sigue funcionando.
 *
 * <p>Si el proveedor entrega siempre la conexión de la transacción, el lock queda dentro de ella y nadie lo ve
 * hasta el commit: una petición repetida espera a que la primera termine, con el timeout del almacén, y el
 * latido no encuentra el lock. En ese caso conviene apagarlo con {@code lockRenewal} en cero.
 *
 * <p>El timeout con que el almacén acota cada sentencia no cubre la espera de una conexión: eso lo fija el
 * pool.
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * Entrega la conexión con que corre una operación del almacén.
     *
     * @return una conexión lista para usar, con el auto-commit activo o enlazada a la transacción del negocio
     * @throws SQLException si no se pudo obtener
     */
    Connection acquire() throws SQLException;

    /**
     * Devuelve la conexión cuando la operación terminó. Por defecto la cierra, que es lo que corresponde a una
     * conexión de un pool. Quien entrega la conexión de una transacción la reemplaza para no cerrarla.
     *
     * @param connection la conexión que entregó {@link #acquire()}
     * @throws SQLException si no se pudo devolver
     */
    default void release(Connection connection) throws SQLException {
        connection.close();
    }

    /**
     * Un proveedor simple: cada operación toma una conexión del {@code DataSource} y la cierra al terminar, y
     * cada sentencia se confirma sola.
     *
     * <p>El {@code DataSource} tiene que entregar conexiones con el auto-commit activo, que es el valor por
     * defecto de JDBC y de HikariCP. Si entrega una con el auto-commit apagado, el proveedor falla en vez de
     * guardar registros que nunca se confirmarían, o de confirmar una transacción ajena. Para una transacción
     * del negocio hay que escribir un proveedor propio.
     *
     * @param dataSource el pool o el {@code DataSource} del servicio
     * @return el proveedor
     */
    static ConnectionProvider of(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return () -> {
            Connection connection = dataSource.getConnection();
            try {
                if (!connection.getAutoCommit()) {
                    throw new IdempotencyStoreException(
                            "The DataSource returned a connection with auto-commit off, so the idempotency records "
                                    + "would never be committed. Turn auto-commit on, or use a ConnectionProvider "
                                    + "that joins the business transaction.");
                }
            } catch (SQLException | RuntimeException notUsable) {
                connection.close();
                throw notUsable;
            }
            return connection;
        };
    }
}
