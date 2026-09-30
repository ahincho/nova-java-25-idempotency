package pe.edu.nova.java.libs.idempotency.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.edu.nova.java.libs.idempotency.Acquisition;
import pe.edu.nova.java.libs.idempotency.Attempt;
import pe.edu.nova.java.libs.idempotency.Completion;
import pe.edu.nova.java.libs.idempotency.Decision;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;
import pe.edu.nova.java.libs.idempotency.IdempotencySettings;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;
import pe.edu.nova.java.libs.idempotency.OwnerToken;
import pe.edu.nova.java.libs.idempotency.ScopedKey;
import pe.edu.nova.java.libs.idempotency.StoredResponse;
import pe.edu.nova.java.libs.idempotency.testing.MutableClock;

/** Lo propio del almacén JDBC, contra un PostgreSQL 17 real: la tabla, la transacción, los timeouts y la purga. */
class JdbcIdempotencyStoreTest {

    private static final String SCOPE = "customer-42";
    private static final String KEY = "marker-KEY-order-1";
    private static final String FINGERPRINT = "fp-1";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static DataSource dataSource;

    private MutableClock clock;
    private JdbcIdempotencyStore store;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(PostgresSupport.dockerAvailable(), "Docker is not available");
        dataSource = PostgresSupport.dataSource();
    }

    @BeforeEach
    void openStore() {
        PostgresSupport.truncate(dataSource);
        PostgresSupport.execute(dataSource, "create table if not exists business_order (id text primary key)");
        PostgresSupport.execute(dataSource, "truncate table business_order");
        clock = new MutableClock();
        store = JdbcIdempotencyStore.builder(dataSource).clock(clock).build();
    }

    // La tabla

    @Test
    void theScriptCreatesTheDocumentedTable() throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        select column_name || ' ' || data_type || case is_nullable when 'NO' then ' not null' else '' end
                          from information_schema.columns
                         where table_schema = 'public' and table_name = 'idempotency_record'
                         order by ordinal_position
                        """)) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }

        assertThat(columns)
                .containsExactly(
                        "scope character varying not null",
                        "idempotency_key character varying not null",
                        "fingerprint character varying not null",
                        "owner_token character varying",
                        "status smallint",
                        "headers ARRAY",
                        "body bytea",
                        "created_at timestamp with time zone not null",
                        "expires_at timestamp with time zone not null");
        assertThat(
                        query(
                                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'idempotency_record' order by 1"))
                .containsExactly("idempotency_record_expires_at", "idempotency_record_pk");
        assertThat(query("select obj_description('idempotency_record'::regclass)"))
                .singleElement()
                .asString()
                .contains("ADR-047");
    }

    @Test
    void theTableRefusesARowThatIsNeitherALockNorAStoredResponse() {
        String base = "insert into idempotency_record (scope, idempotency_key, fingerprint, %s, created_at, expires_at)"
                + " values ('s', 'k', 'f', %s, now(), now())";

        assertThatThrownBy(() -> PostgresSupport.execute(
                        dataSource, base.formatted("owner_token, status, headers, body", "'t', 200, '{}', ''::bytea")))
                .hasMessageContaining("idempotency_record_state");
        assertThatThrownBy(() -> PostgresSupport.execute(dataSource, base.formatted("status", "200")))
                .hasMessageContaining("idempotency_record_state");
        assertThatThrownBy(() -> PostgresSupport.execute(dataSource, base.formatted("owner_token", "null")))
                .hasMessageContaining("idempotency_record_state");
        assertThatThrownBy(() -> PostgresSupport.execute(
                        dataSource, base.formatted("status, headers, body", "42, '{}', ''::bytea")))
                .as("a status that is not HTTP")
                .hasMessageContaining("idempotency_record_state");
    }

    @Test
    void aStoredResponseIsWrittenIntoTheDocumentedColumns() throws SQLException {
        StoredResponse response = new StoredResponse(
                201,
                Map.of("location", List.of("/orders/1"), "content-language", List.of("es", "en")),
                "{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        ScopedKey key = new ScopedKey(SCOPE, KEY);
        OwnerToken owner = OwnerToken.generate();
        store.acquire(key, owner, FINGERPRINT, LOCK_TTL, TIMEOUT);

        try (Connection connection = dataSource.getConnection();
                ResultSet lock = connection
                        .createStatement()
                        .executeQuery("select owner_token, status, headers, body, created_at, expires_at"
                                + " from idempotency_record")) {
            assertThat(lock.next()).isTrue();
            assertThat(lock.getString("owner_token")).isEqualTo(owner.value());
            assertThat(lock.getObject("status")).as("no response yet").isNull();
            assertThat(lock.getArray("headers")).isNull();
            assertThat(lock.getBytes("body")).isNull();
            assertThat(lock.getObject("created_at", OffsetDateTime.class).toInstant())
                    .isEqualTo(clock.instant());
            assertThat(lock.getObject("expires_at", OffsetDateTime.class).toInstant())
                    .isEqualTo(clock.instant().plus(LOCK_TTL));
        }

        store.complete(key, owner, response, RETENTION, TIMEOUT);

        try (Connection connection = dataSource.getConnection();
                ResultSet done = connection
                        .createStatement()
                        .executeQuery("select scope, idempotency_key, fingerprint, owner_token, status, headers, body,"
                                + " expires_at from idempotency_record")) {
            assertThat(done.next()).isTrue();
            assertThat(done.getString("scope")).isEqualTo(SCOPE);
            assertThat(done.getString("idempotency_key")).isEqualTo(KEY);
            assertThat(done.getString("fingerprint")).isEqualTo(FINGERPRINT);
            assertThat(done.getString("owner_token"))
                    .as("no owner once completed")
                    .isNull();
            assertThat(done.getInt("status")).isEqualTo(201);
            Array headers = done.getArray("headers");
            assertThat((String[]) headers.getArray())
                    .containsExactlyInAnyOrder(
                            "location", "/orders/1", "content-language", "es", "content-language", "en");
            assertThat(done.getBytes("body")).isEqualTo("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
            assertThat(done.getObject("expires_at", OffsetDateTime.class).toInstant())
                    .as("the retention, not the lock ttl")
                    .isEqualTo(clock.instant().plus(RETENTION));
        }
    }

    @Test
    void aCustomTableInAnotherSchemaWorksAndLeavesTheDefaultOneAlone() throws SQLException {
        PostgresSupport.execute(dataSource, "drop schema if exists orders_test cascade");
        PostgresSupport.execute(dataSource, "create schema orders_test");
        PostgresSupport.execute(dataSource, "set search_path to orders_test; " + PostgresSupport.schemaScript());
        JdbcIdempotencyStore custom = JdbcIdempotencyStore.builder(dataSource)
                .clock(clock)
                .tableName("orders_test.idempotency_record")
                .build();
        ScopedKey key = new ScopedKey(SCOPE, KEY);
        OwnerToken owner = OwnerToken.generate();
        StoredResponse response = StoredResponse.of(200, new byte[] {1});

        assertThat(custom.acquire(key, owner, FINGERPRINT, LOCK_TTL, TIMEOUT)).isEqualTo(new Acquisition.Acquired());
        assertThat(custom.complete(key, owner, response, RETENTION, TIMEOUT)).isTrue();
        assertThat(custom.acquire(key, OwnerToken.generate(), FINGERPRINT, LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Completed(FINGERPRINT, response));

        try {
            assertThat(query("select count(*) from orders_test.idempotency_record"))
                    .containsExactly("1");
            assertThat(query("select count(*) from public.idempotency_record")).containsExactly("0");
            clock.advance(RETENTION.plusMinutes(1));
            assertThat(custom.purgeExpired(10, TIMEOUT)).isEqualTo(1);
        } finally {
            PostgresSupport.execute(dataSource, "drop schema orders_test cascade");
        }
    }

    @Test
    void aRecordWithCorruptHeadersIsReportedInsteadOfBeingReplayed() {
        PostgresSupport.execute(
                dataSource,
                "insert into idempotency_record (scope, idempotency_key, fingerprint, status, headers, body,"
                        + " created_at, expires_at) values ('s', 'k', 'f', 200, '{orphan}', ''::bytea, now(),"
                        + " now() + interval '1 day')");
        JdbcIdempotencyStore realClock =
                JdbcIdempotencyStore.builder(dataSource).build();

        assertThatThrownBy(
                        () -> realClock.acquire(new ScopedKey("s", "k"), OwnerToken.generate(), "f", LOCK_TTL, TIMEOUT))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessageContaining("corrupt");
    }

    // La concurrencia, con el motor

    @Test
    void ofTwoSimultaneousAttemptsWithTheSameKeyOneExecutesAndTheOtherIsInProgress() throws Exception {
        try (IdempotencyEngine engine = new IdempotencyEngine(store, withoutHeartbeat())) {
            for (int round = 0; round < 25; round++) {
                List<Decision> decisions = raceTwo(engine, "round-" + round, FINGERPRINT, FINGERPRINT);

                assertThat(decisions)
                        .filteredOn(Decision.Execute.class::isInstance)
                        .hasSize(1);
                assertThat(decisions)
                        .filteredOn(Decision.InProgress.class::isInstance)
                        .hasSize(1);
            }
        }
    }

    @Test
    void ofTwoSimultaneousAttemptsWithOtherContentOneExecutesAndTheOtherIsReused() throws Exception {
        try (IdempotencyEngine engine = new IdempotencyEngine(store, withoutHeartbeat())) {
            for (int round = 0; round < 10; round++) {
                List<Decision> decisions = raceTwo(engine, "round-" + round, "fp-a", "fp-b");

                assertThat(decisions)
                        .filteredOn(Decision.Execute.class::isInstance)
                        .hasSize(1);
                assertThat(decisions)
                        .filteredOn(Decision.KeyReused.class::isInstance)
                        .hasSize(1);
            }
        }
    }

    @Test
    void aFullRequestGoesThroughTheEngineAndTheDatabase() {
        try (IdempotencyEngine engine = new IdempotencyEngine(store)) {
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.InProgress.class);
            StoredResponse response = new StoredResponse(
                    201,
                    Map.of("Location", List.of("/orders/1"), "Set-Cookie", List.of("session=abc")),
                    "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(engine.complete(attempt, response)).isEqualTo(Completion.STORED);

            Decision.Replay replay = (Decision.Replay) engine.begin(KEY, SCOPE, FINGERPRINT);
            assertThat(replay.response().headers()).containsOnlyKeys("location");
            assertThat(engine.begin(KEY, SCOPE, "another")).isInstanceOf(Decision.KeyReused.class);
            assertThat(engine.begin(KEY, "another-customer", FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        }
    }

    @Test
    void aServerErrorLeavesNothingInTheDatabase() throws SQLException {
        try (IdempotencyEngine engine = new IdempotencyEngine(store)) {
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));

            assertThat(engine.complete(attempt, StoredResponse.of(503, new byte[0])))
                    .isEqualTo(Completion.RELEASED);

            assertThat(query("select count(*) from idempotency_record")).containsExactly("0");
        }
    }

    // La transacción del negocio

    @Test
    void theResponseAndTheBusinessChangeAreConfirmedInTheSameCommit() throws Exception {
        TransactionalProvider provider = new TransactionalProvider(dataSource);
        JdbcIdempotencyStore joined =
                JdbcIdempotencyStore.builder(provider).clock(clock).build();
        StoredResponse response = StoredResponse.of(201, "{\"order\":1}".getBytes(StandardCharsets.UTF_8));
        try (IdempotencyEngine engine = new IdempotencyEngine(joined, withoutHeartbeat())) {
            // El lock se toma antes de abrir la transacción: queda confirmado y a la vista de las demás.
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
            assertThat(recordState()).isEqualTo("lock");
            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.InProgress.class);

            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                provider.bind(transaction);
                try {
                    transaction.createStatement().execute("insert into business_order values ('order-1')");
                    assertThat(engine.complete(attempt, response)).isEqualTo(Completion.STORED);

                    assertThat(orders())
                            .as("the order is not visible before the commit")
                            .isEmpty();
                    assertThat(recordState())
                            .as("nor is the response: the record is still the lock")
                            .isEqualTo("lock");
                } finally {
                    provider.unbind();
                }
                transaction.commit();
            }

            assertThat(orders()).containsExactly("order-1");
            assertThat(recordState()).isEqualTo("response");
            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(response));
        }
    }

    @Test
    void aRollbackTakesTheResponseWithTheBusinessChangeAndTheLockExpiresLater() throws Exception {
        TransactionalProvider provider = new TransactionalProvider(dataSource);
        JdbcIdempotencyStore joined =
                JdbcIdempotencyStore.builder(provider).clock(clock).build();
        try (IdempotencyEngine engine = new IdempotencyEngine(joined, withoutHeartbeat())) {
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));

            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                provider.bind(transaction);
                try {
                    transaction.createStatement().execute("insert into business_order values ('order-1')");
                    engine.complete(attempt, StoredResponse.of(201, new byte[0]));
                } finally {
                    provider.unbind();
                }
                transaction.rollback();
            }

            assertThat(orders()).as("the business change was undone").isEmpty();
            assertThat(recordState())
                    .as("and so was the response: only the lock remains, so the operation is not replayed")
                    .isEqualTo("lock");
            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.InProgress.class);

            clock.advance(LOCK_TTL.plusSeconds(1));
            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT))
                    .as("once the lock expires, the client can retry")
                    .isInstanceOf(Decision.Execute.class);
        }
    }

    @Test
    void anOperationThatFailsReleasesTheLockOutsideTheTransaction() throws Exception {
        TransactionalProvider provider = new TransactionalProvider(dataSource);
        JdbcIdempotencyStore joined =
                JdbcIdempotencyStore.builder(provider).clock(clock).build();
        try (IdempotencyEngine engine = new IdempotencyEngine(joined, withoutHeartbeat())) {
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                transaction.createStatement().execute("insert into business_order values ('order-1')");
                transaction.rollback();
            }

            assertThat(engine.release(attempt)).isEqualTo(Completion.RELEASED);

            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isInstanceOf(Decision.Execute.class);
        }
    }

    @Test
    void theHeartbeatKeepsTheLockAliveWhileTheBusinessTransactionIsOpen() throws Exception {
        TransactionalProvider provider = new TransactionalProvider(dataSource);
        JdbcIdempotencyStore joined = JdbcIdempotencyStore.builder(provider).build();
        IdempotencySettings settings = IdempotencySettings.defaults()
                .withLockTtl(Duration.ofSeconds(1))
                .withLockRenewal(Duration.ofMillis(100));
        StoredResponse response = StoredResponse.of(201, new byte[] {7});
        try (IdempotencyEngine engine = new IdempotencyEngine(joined, settings)) {
            Attempt attempt = attempt(engine.begin(KEY, SCOPE, FINGERPRINT));
            try (Connection transaction = dataSource.getConnection()) {
                transaction.setAutoCommit(false);
                provider.bind(transaction);
                try {
                    transaction.createStatement().execute("insert into business_order values ('slow-order')");

                    // La operación tarda el doble que el lock. El latido corre en otro hilo, sin transacción.
                    Thread.sleep(2_200);

                    assertThat(CompletableFuture.supplyAsync(() -> engine.begin(KEY, SCOPE, FINGERPRINT))
                                    .get(30, TimeUnit.SECONDS))
                            .as("a duplicate from another thread finds the lock renewed from outside the transaction")
                            .isInstanceOf(Decision.InProgress.class);
                    assertThat(engine.complete(attempt, response)).isEqualTo(Completion.STORED);
                } finally {
                    provider.unbind();
                }
                transaction.commit();
            }

            assertThat(engine.begin(KEY, SCOPE, FINGERPRINT)).isEqualTo(new Decision.Replay(response));
            assertThat(orders()).containsExactly("slow-order");
        }
    }

    @Test
    void aReleaseThatFailsAfterTheOperationDoesNotFailIt() {
        ConnectionProvider brokenRelease = new ConnectionProvider() {
            @Override
            public Connection acquire() throws SQLException {
                return dataSource.getConnection();
            }

            @Override
            public void release(Connection connection) throws SQLException {
                connection.close();
                throw new SQLException("could not give the connection back");
            }
        };
        JdbcIdempotencyStore leaky =
                JdbcIdempotencyStore.builder(brokenRelease).clock(clock).build();

        assertThat(leaky.acquire(new ScopedKey(SCOPE, KEY), OwnerToken.generate(), FINGERPRINT, LOCK_TTL, TIMEOUT))
                .isEqualTo(new Acquisition.Acquired());
    }

    // Los timeouts, la purga y los errores

    @Test
    void everyStatementHasATimeoutSoAStuckRowLockCannotHangTheCaller() throws Exception {
        ScopedKey key = new ScopedKey(SCOPE, KEY);
        OwnerToken owner = OwnerToken.generate();
        store.acquire(key, owner, FINGERPRINT, LOCK_TTL, TIMEOUT);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select 1 from idempotency_record for update");
            long started = System.nanoTime();

            assertThatThrownBy(() -> store.renew(key, owner, LOCK_TTL, Duration.ofSeconds(1)))
                    .isInstanceOf(IdempotencyStoreException.class)
                    .hasMessageContaining("renew")
                    .hasMessageContaining("57014")
                    .hasMessageNotContaining(KEY)
                    .hasMessageNotContaining(SCOPE)
                    .hasNoCause();

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("it gave up after the timeout, not after the lock was released")
                    .isBetween(Duration.ofMillis(900), Duration.ofSeconds(8));
            holder.rollback();
        }
        assertThat(store.renew(key, owner, LOCK_TTL, TIMEOUT))
                .as("and the store still works")
                .isTrue();
    }

    @Test
    void purgeDoesNotWaitForARowThatAnotherTransactionHolds() throws Exception {
        for (int i = 0; i < 5; i++) {
            store.acquire(
                    new ScopedKey(SCOPE, "k" + i), OwnerToken.generate(), FINGERPRINT, Duration.ofSeconds(10), TIMEOUT);
        }
        clock.advance(Duration.ofMinutes(1));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement()
                    .execute("select 1 from idempotency_record where idempotency_key = 'k2' for update");
            long started = System.nanoTime();

            assertThat(store.purgeExpired(100, Duration.ofSeconds(2)))
                    .as("all but the held one")
                    .isEqualTo(4);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            holder.rollback();
        }
        assertThat(store.purgeExpired(100, TIMEOUT)).isEqualTo(1);
    }

    @Test
    void purgeDeletesInBatchesOfTheGivenSize() {
        for (int i = 0; i < 7; i++) {
            store.acquire(
                    new ScopedKey(SCOPE, "k" + i), OwnerToken.generate(), FINGERPRINT, Duration.ofSeconds(10), TIMEOUT);
        }
        clock.advance(Duration.ofMinutes(1));

        assertThat(store.purgeExpired(3, TIMEOUT)).isEqualTo(3);
        assertThat(store.purgeExpired(3, TIMEOUT)).isEqualTo(3);
        assertThat(store.purgeExpired(3, TIMEOUT)).isEqualTo(1);
        assertThat(store.purgeExpired(3, TIMEOUT)).isZero();
    }

    @Test
    void aDatabaseErrorSaysWhichOperationFailedAndNeverQuotesTheKey() {
        JdbcIdempotencyStore missingTable = JdbcIdempotencyStore.builder(dataSource)
                .tableName("no_such_table")
                .build();
        ScopedKey key = new ScopedKey(SCOPE, KEY);

        assertThatThrownBy(() -> missingTable.acquire(key, OwnerToken.generate(), FINGERPRINT, LOCK_TTL, TIMEOUT))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessage("The store failed to acquire (SQLState 42P01)")
                .hasNoCause();
        assertThatThrownBy(() -> missingTable.complete(
                        key, OwnerToken.generate(), StoredResponse.of(200, new byte[0]), RETENTION, TIMEOUT))
                .hasMessage("The store failed to complete (SQLState 42P01)");
        assertThatThrownBy(() -> missingTable.release(key, OwnerToken.generate(), TIMEOUT))
                .hasMessage("The store failed to release (SQLState 42P01)");
        assertThatThrownBy(() -> missingTable.renew(key, OwnerToken.generate(), LOCK_TTL, TIMEOUT))
                .hasMessage("The store failed to renew (SQLState 42P01)");
        assertThatThrownBy(() -> missingTable.purgeExpired(10, TIMEOUT))
                .hasMessage("The store failed to purge expired records (SQLState 42P01)");
    }

    @Test
    void theStoreIsDeclaredPersistent() {
        assertThat(store.persistent()).isTrue();
    }

    private static IdempotencySettings withoutHeartbeat() {
        return IdempotencySettings.defaults().withLockRenewal(Duration.ZERO);
    }

    private static Attempt attempt(Decision decision) {
        assertThat(decision).isInstanceOf(Decision.Execute.class);
        return ((Decision.Execute) decision).attempt();
    }

    private static List<Decision> raceTwo(
            IdempotencyEngine engine, String key, String fingerprintA, String fingerprintB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<Decision> first = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return engine.begin(key, SCOPE, fingerprintA);
            });
            Future<Decision> second = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return engine.begin(key, SCOPE, fingerprintB);
            });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Lo que dice la base a una conexión que no participa de ninguna transacción. */
    private static String recordState() throws SQLException {
        List<String> states = query("""
                select case when owner_token is not null then 'lock' else 'response' end
                  from idempotency_record
                 where scope = '%s' and idempotency_key = '%s'
                """.formatted(SCOPE, KEY));
        return states.isEmpty() ? "absent" : states.get(0);
    }

    private static List<String> orders() throws SQLException {
        return query("select id from business_order order by id");
    }

    private static List<String> query(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
