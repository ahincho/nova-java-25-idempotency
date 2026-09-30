package pe.edu.nova.java.libs.idempotency.jdbc;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.IdempotencyStore;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;

/**
 * El almacén de idempotencia para PostgreSQL, con JDBC puro (ADR-047).
 *
 * <p>Los registros viven en una tabla que el servicio crea con el script
 * {@code db/nova/idempotency/postgresql/V1__create_idempotency_record.sql}, que viene dentro de este módulo.
 * Cada operación es una sola sentencia atómica:
 *
 * <ul>
 *   <li>{@code acquire} inserta la clave, o, si ya existe y venció, la toma con la misma sentencia
 *       ({@code INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= ahora}). De varias peticiones
 *       simultáneas gana exactamente una: PostgreSQL deja escribir a una, y a las otras les revisa la
 *       condición contra la fila de la ganadora. Solo entonces lee la fila que encontró;
 *   <li>{@code complete}, {@code release} y {@code renew} son un {@code UPDATE} o un {@code DELETE} con
 *       {@code owner_token = ?} en el {@code WHERE}: solo lo hace el dueño;
 *   <li>{@code purgeExpired} borra por lotes, con {@code FOR UPDATE SKIP LOCKED}, así que no espera a una
 *       fila que otra transacción está tomando.
 * </ul>
 *
 * <p><strong>Toda sentencia lleva {@code setQueryTimeout}</strong> con el timeout que fija el núcleo, redondeado
 * hacia arriba a segundos enteros. El tiempo se mide con el reloj de la aplicación (un {@link Clock}), así que
 * las réplicas del servicio tienen que estar sincronizadas; lo que protege el registro es el token de dueño,
 * no el reloj.
 *
 * <p><strong>Las transacciones.</strong> Con un {@code DataSource} cada operación usa su propia conexión y se
 * confirma sola. Con un {@link ConnectionProvider} que entrega la conexión de la transacción del negocio, el
 * registro de la clave y el cambio del negocio se confirman en el mismo commit. El almacén nunca hace commit
 * ni rollback. Si la transacción usa el nivel REPEATABLE READ o SERIALIZABLE, PostgreSQL puede rechazar dos
 * intentos simultáneos con un error de serialización.
 *
 * <p><strong>Los errores.</strong> Un fallo de la base se convierte en una {@link IdempotencyStoreException}
 * que dice qué operación falló y con qué SQLState, y <em>no encadena la {@link SQLException}</em>: el mensaje
 * del servidor cita la fila cuando una restricción se rompe, y la fila lleva la clave y el cuerpo.
 *
 * <p>Es seguro usarlo desde varios hilos.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    /** El nombre de la tabla que crea el script. */
    public static final String DEFAULT_TABLE = "idempotency_record";

    /** Cuántas veces se vuelve a intentar una clave cuya fila cambió entre la sentencia y la lectura. */
    private static final int MAX_ACQUIRE_ATTEMPTS = 5;

    private static final Pattern TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}([.][A-Za-z_][A-Za-z0-9_]{0,62})?");

    private final ConnectionProvider connections;
    private final Clock clock;
    private final String insertSql;
    private final String selectSql;
    private final String completeSql;
    private final String releaseSql;
    private final String renewSql;
    private final String purgeSql;

    private JdbcIdempotencyStore(ConnectionProvider connections, Clock clock, String table) {
        this.connections = connections;
        this.clock = clock;
        this.insertSql = """
                insert into %s as r (scope, idempotency_key, fingerprint, owner_token, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (scope, idempotency_key) do update
                   set fingerprint = excluded.fingerprint,
                       owner_token = excluded.owner_token,
                       status = null,
                       headers = null,
                       body = null,
                       created_at = excluded.created_at,
                       expires_at = excluded.expires_at
                 where r.expires_at <= ?
                """.formatted(table);
        this.selectSql = """
                select fingerprint, owner_token, status, headers, body, expires_at
                  from %s
                 where scope = ? and idempotency_key = ?
                """.formatted(table);
        this.completeSql = """
                update %s
                   set owner_token = null, status = ?, headers = ?, body = ?, expires_at = ?
                 where scope = ? and idempotency_key = ? and owner_token = ?
                """.formatted(table);
        this.releaseSql = """
                delete from %s
                 where scope = ? and idempotency_key = ? and owner_token = ?
                """.formatted(table);
        this.renewSql = """
                update %s
                   set expires_at = ?
                 where scope = ? and idempotency_key = ? and owner_token = ?
                """.formatted(table);
        this.purgeSql = """
                delete from %1$s
                 where (scope, idempotency_key) in (
                       select scope, idempotency_key
                         from %1$s
                        where expires_at <= ?
                        order by expires_at
                        limit ?
                          for update skip locked)
                """.formatted(table);
    }

    /**
     * Empieza a armar un almacén que usa un {@code DataSource}: cada operación toma una conexión, y cada
     * sentencia se confirma sola.
     *
     * @param dataSource el pool o el {@code DataSource} del servicio, con el auto-commit activo
     * @return el armador
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(ConnectionProvider.of(dataSource));
    }

    /**
     * Empieza a armar un almacén que pide sus conexiones a un {@link ConnectionProvider}, por ejemplo uno que
     * entrega la conexión de la transacción del negocio.
     *
     * @param connections de dónde salen las conexiones
     * @return el armador
     */
    public static Builder builder(ConnectionProvider connections) {
        return new Builder(Objects.requireNonNull(connections, "connections"));
    }

    @Override
    public Acquisition acquire(
            ScopedKey key, OwnerToken owner, String fingerprint, Duration lockTtl, Duration timeout) {
        return execute("acquire", connection -> {
            for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
                Instant now = clock.instant();
                if (take(connection, key, owner, fingerprint, now, now.plus(lockTtl), timeout)) {
                    return new Acquisition.Acquired();
                }
                Found found = find(connection, key, timeout);
                if (found != null && found.expiresAt().isAfter(now)) {
                    return found.asAcquisition();
                }
                // La fila se liberó, o venció, o la renovó su dueño entre las dos sentencias: se vuelve a intentar.
            }
            throw new IdempotencyStoreException("The store could not acquire the key: its record kept changing");
        });
    }

    @Override
    public boolean complete(
            ScopedKey key, OwnerToken owner, StoredResponse response, Duration retention, Duration timeout) {
        return execute("complete", connection -> {
            Array headers = connection.createArrayOf("text", flatten(response.headers()));
            try (PreparedStatement statement = prepare(connection, completeSql, timeout)) {
                statement.setInt(1, response.status());
                statement.setArray(2, headers);
                statement.setBytes(3, response.body());
                statement.setObject(4, at(clock.instant().plus(retention)));
                statement.setString(5, key.scope());
                statement.setString(6, key.key());
                statement.setString(7, owner.value());
                return statement.executeUpdate() == 1;
            } finally {
                headers.free();
            }
        });
    }

    @Override
    public boolean release(ScopedKey key, OwnerToken owner, Duration timeout) {
        return execute("release", connection -> {
            try (PreparedStatement statement = prepare(connection, releaseSql, timeout)) {
                statement.setString(1, key.scope());
                statement.setString(2, key.key());
                statement.setString(3, owner.value());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean renew(ScopedKey key, OwnerToken owner, Duration lockTtl, Duration timeout) {
        return execute("renew", connection -> {
            try (PreparedStatement statement = prepare(connection, renewSql, timeout)) {
                statement.setObject(1, at(clock.instant().plus(lockTtl)));
                statement.setString(2, key.scope());
                statement.setString(3, key.key());
                statement.setString(4, owner.value());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int purgeExpired(int limit, Duration timeout) {
        return execute("purge expired records", connection -> {
            try (PreparedStatement statement = prepare(connection, purgeSql, timeout)) {
                statement.setObject(1, at(clock.instant()));
                statement.setInt(2, limit);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private boolean take(
            Connection connection,
            ScopedKey key,
            OwnerToken owner,
            String fingerprint,
            Instant now,
            Instant expiresAt,
            Duration timeout)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, insertSql, timeout)) {
            statement.setString(1, key.scope());
            statement.setString(2, key.key());
            statement.setString(3, fingerprint);
            statement.setString(4, owner.value());
            statement.setObject(5, at(now));
            statement.setObject(6, at(expiresAt));
            statement.setObject(7, at(now));
            return statement.executeUpdate() == 1;
        }
    }

    private Found find(Connection connection, ScopedKey key, Duration timeout) throws SQLException {
        try (PreparedStatement statement = prepare(connection, selectSql, timeout)) {
            statement.setString(1, key.scope());
            statement.setString(2, key.key());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                String fingerprint = row.getString("fingerprint");
                Instant expiresAt =
                        row.getObject("expires_at", OffsetDateTime.class).toInstant();
                if (row.getString("owner_token") != null) {
                    return new Found(fingerprint, expiresAt, null);
                }
                Array headers = row.getArray("headers");
                try {
                    StoredResponse response = new StoredResponse(
                            row.getInt("status"), unflatten((String[]) headers.getArray()), row.getBytes("body"));
                    return new Found(fingerprint, expiresAt, response);
                } finally {
                    headers.free();
                }
            }
        }
    }

    /** Prepara una sentencia con el timeout de la llamada: ninguna sentencia del almacén espera sin límite. */
    private static PreparedStatement prepare(Connection connection, String sql, Duration timeout) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(seconds(timeout));
        } catch (SQLException | RuntimeException failure) {
            statement.close();
            throw failure;
        }
        return statement;
    }

    /** El timeout de JDBC va en segundos enteros; uno menor que un segundo se redondea hacia arriba. */
    private static int seconds(Duration timeout) {
        long seconds = (timeout.toMillis() + 999) / 1000;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Los headers como pares nombre y valor en un solo arreglo, que es como los guarda la tabla. */
    private static String[] flatten(Map<String, List<String>> headers) {
        List<String> flat = new ArrayList<>();
        headers.forEach((name, values) -> values.forEach(value -> {
            flat.add(name);
            flat.add(value);
        }));
        return flat.toArray(new String[0]);
    }

    private static Map<String, List<String>> unflatten(String[] flat) {
        if (flat.length % 2 != 0) {
            throw new IdempotencyStoreException("The stored headers of a record are corrupt");
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < flat.length; i += 2) {
            headers.computeIfAbsent(flat[i], name -> new ArrayList<>()).add(flat[i + 1]);
        }
        return headers;
    }

    /** Corre una operación con una conexión del proveedor y la devuelve al terminar. */
    private <T> T execute(String operation, Operation<T> body) {
        Connection connection;
        try {
            connection = connections.acquire();
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
        try {
            return body.run(connection);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        } finally {
            try {
                connections.release(connection);
            } catch (SQLException ignored) {
                // La operación ya terminó, y una conexión que no se deja devolver la descarta el pool.
            }
        }
    }

    private static IdempotencyStoreException failed(String operation, SQLException failure) {
        String state = failure.getSQLState() != null ? failure.getSQLState() : "unknown";
        // No se encadena la SQLException: su mensaje puede citar la fila, y la fila lleva la clave y el cuerpo.
        return new IdempotencyStoreException("The store failed to " + operation + " (SQLState " + state + ")");
    }

    @FunctionalInterface
    private interface Operation<T> {

        T run(Connection connection) throws SQLException;
    }

    /** Lo que se leyó de la fila de una clave. */
    private record Found(String fingerprint, Instant expiresAt, StoredResponse response) {

        Acquisition asAcquisition() {
            return response == null
                    ? new Acquisition.InFlight(fingerprint)
                    : new Acquisition.Completed(fingerprint, response);
        }
    }

    /** Arma un {@link JdbcIdempotencyStore}. */
    public static final class Builder {

        private final ConnectionProvider connections;
        private String tableName = DEFAULT_TABLE;
        private Clock clock = Clock.systemUTC();

        private Builder(ConnectionProvider connections) {
            this.connections = connections;
        }

        /**
         * La tabla de los registros, si no es la que crea el script.
         *
         * @param tableName un nombre, o esquema y nombre separados por un punto, como
         *                  {@code orders.idempotency_record}; solo letras, dígitos y guiones bajos
         * @return este armador
         * @throws IllegalArgumentException si el nombre no es un identificador simple
         */
        public Builder tableName(String tableName) {
            Objects.requireNonNull(tableName, "tableName");
            if (!TABLE_NAME.matcher(tableName).matches()) {
                throw new IllegalArgumentException(
                        "The table name must be an identifier, optionally prefixed by a schema and a dot");
            }
            this.tableName = tableName;
            return this;
        }

        /**
         * El reloj con que se vencen los locks y las respuestas; por defecto el del sistema. Una prueba
         * puede darle uno que ella adelanta.
         *
         * @param clock el reloj
         * @return este armador
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Arma el almacén.
         *
         * @return el almacén
         */
        public JdbcIdempotencyStore build() {
            return new JdbcIdempotencyStore(connections, clock, tableName);
        }
    }
}
