/**
 * El almacén de la capacidad de idempotencia para PostgreSQL, con JDBC puro (ADR-047).
 *
 * <p>{@link pe.edu.nova.java.libs.idempotency.jdbc.JdbcIdempotencyStore} guarda los registros en una tabla
 * que el servicio crea con el script {@code db/nova/idempotency/postgresql/V1__create_idempotency_record.sql},
 * que viene dentro de este módulo y se aplica con el migrador del servicio. Toma la clave con una sola
 * sentencia atómica y completa, renueva y libera solo con el token de dueño.
 *
 * <p>Se puede sumar a la transacción del negocio: recibe la conexión de quien lo llama a través de un
 * {@link pe.edu.nova.java.libs.idempotency.jdbc.ConnectionProvider}, y el registro de la clave y el cambio
 * del negocio se confirman en el mismo commit. Un {@code DataSource} directo es la opción simple.
 *
 * <p>El módulo solo depende de {@code java.sql}: el driver de PostgreSQL lo trae el servicio.
 */
package pe.edu.nova.java.libs.idempotency.jdbc;
