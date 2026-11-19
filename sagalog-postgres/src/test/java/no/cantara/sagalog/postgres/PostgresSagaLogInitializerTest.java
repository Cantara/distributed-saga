package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static no.cantara.sagalog.postgres.TwoProcesses.admin;
import static no.cantara.sagalog.postgres.TwoProcesses.execute;
import static no.cantara.sagalog.postgres.TwoProcesses.port;
import static no.cantara.sagalog.postgres.TwoProcesses.query;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * The production initializer against the embedded server: timeouts (sp-5), TLS settings (sp-13), and opening a
 * database made by 2.1.0.
 */
@Test(groups = "integration")
public class PostgresSagaLogInitializerTest {

    private static final String SCHEMA = "sagalog_init";

    @BeforeMethod
    public void dropSchema() throws SQLException {
        execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    private static Map<String, String> configuration(String... keysAndValues) {
        Map<String, String> configuration = new HashMap<>(Map.of(
                "cluster.owner", SCHEMA,
                "cluster.name", "init",
                "cluster.instance-id", "01",
                "postgres.driver.host", "localhost",
                "postgres.driver.port", String.valueOf(port()),
                "postgres.driver.user", "postgres",
                "postgres.driver.password", "postgres",
                "postgres.driver.database", "postgres",
                "connection-pool.max-size", "4"));
        for (int i = 0; i < keysAndValues.length; i += 2) {
            configuration.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return configuration;
    }

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    public void thatTheDefaultsAreTheDriversOwn() throws Exception {
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initialize(configuration());
        try (Connection connection = pool.dataSource.getConnection()) {
            assertEquals(show(connection, "statement_timeout"), "0");
            assertEquals(show(connection, "lock_timeout"), "0");
            assertEquals(connection.getNetworkTimeout(), 0, "the socket timeout");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatTheTimeoutsCanBeConfigured() throws Exception {
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initialize(configuration(
                "postgres.statement-timeout", "2500",
                "postgres.lock-timeout", "1000",
                "postgres.driver.socket-timeout", "90000",
                "postgres.driver.tcp-keep-alive", "true",
                "connection-pool.connection-timeout", "1500"));
        try (Connection connection = pool.dataSource.getConnection()) {
            assertEquals(show(connection, "statement_timeout"), "2500ms");
            assertEquals(show(connection, "lock_timeout"), "1s");
            assertEquals(connection.getNetworkTimeout(), 90_000);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * sp-5: a write that waits on a table lock held by another session fails within the configured bound, and close()
     * is not held up for longer than that.
     */
    @Test
    public void thatAWriteWaitingOnALockFailsWithinTheTimeout() throws Exception {
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initialize(configuration(
                "postgres.lock-timeout", "500",
                "postgres.statement-timeout", "1000"));
        Connection blocker = admin();
        try {
            SagaLogId id = pool.idFor("01", "slow");
            SagaLog log = pool.connect(id);
            log.write(log.builder().startSaga("e", "s", "{}")).join();
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("LOCK TABLE \"" + SCHEMA + "\".\"SAGALOG_init_01_slow\" IN ACCESS EXCLUSIVE MODE");
            }
            long start = System.nanoTime();
            CompletableFuture<Object> write = CompletableFuture.supplyAsync(() -> log.write(log.builder().startAction("e", "a")).join(),
                    task -> new Thread(task).start());
            try {
                write.get(5, TimeUnit.SECONDS);
                fail("the write succeeded while the table was locked");
            } catch (ExecutionException expected) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                assertTrue(ms < 5000, "failed after " + ms + " ms");
                assertTrue(String.valueOf(TwoProcesses.unwrap(expected).getMessage()).contains("slow"),
                        "the failure names the log: " + TwoProcesses.unwrap(expected));
            } catch (TimeoutException e) {
                fail("the write waited on the lock for more than 5 s");
            }
            long closeStart = System.nanoTime();
            pool.remove(id);
            assertTrue((System.nanoTime() - closeStart) / 1_000_000 < 5000, "close() waited on the lock");
        } finally {
            blocker.rollback();
            blocker.close();
            pool.shutdown();
        }
    }

    /**
     * sp-13: the TLS mode reaches the driver. The embedded server has no TLS, so requiring it fails.
     */
    @Test
    public void thatTheTlsModeReachesTheDriver() {
        expectThrows(RuntimeException.class, () -> new PostgresSagaLogInitializer().initialize(configuration(
                "postgres.driver.sslmode", "require")).shutdown());
        new PostgresSagaLogInitializer().initialize(configuration("postgres.driver.sslmode", "disable")).shutdown();
    }

    /**
     * Any other driver property passes through under postgres.driver.property.
     */
    @Test
    public void thatOtherDriverPropertiesPassThrough() throws Exception {
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initialize(configuration(
                "postgres.driver.property.ApplicationName", "saga-log-test"));
        try (Connection connection = pool.dataSource.getConnection()) {
            assertEquals(show(connection, "application_name"), "saga-log-test");
        } finally {
            pool.shutdown();
        }
    }

    /**
     * A database made by 2.1.0: its Locks table has no owner_token column. Opening it adds the column, and the logs
     * listed there open with their keys.
     */
    @Test
    public void thatALocksTableFrom210IsMigratedOnOpen() throws Exception {
        execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        execute("CREATE TABLE \"" + SCHEMA + "\".\"Locks\" (namespace varchar NOT NULL, instance_id varchar NOT NULL,"
                + " log_id varchar NOT NULL, lock_key bigint NOT NULL, PRIMARY KEY (namespace, instance_id, log_id))");
        execute("INSERT INTO \"" + SCHEMA + "\".\"Locks\" VALUES ('init', '01', 'old', 4711)");
        execute("CREATE TABLE \"" + SCHEMA + "\".\"SAGALOG_init_01_old\" (entry_id uuid NOT NULL, txid varchar NOT NULL,"
                + " entry_type smallint NOT NULL, node_id varchar NOT NULL, saga_name varchar NULL, data text NULL,"
                + " PRIMARY KEY (entry_id, txid))");
        for (int i = 0; i < 2; i++) { // the migration runs again without harm
            PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initialize(configuration());
            try {
                SagaLog log = pool.connect(pool.idFor("01", "old"));
                log.write(log.builder().startSaga("e" + i, "s", "{}")).join();
                assertEquals(log.readIncompleteSagas().count(), i + 1L);
                assertEquals(TwoProcesses.lockKey(SCHEMA, "01", "old"), 4711L);
            } finally {
                pool.shutdown();
            }
        }
        assertEquals(query("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'Locks' AND column_name = 'owner_token'"), List.of("1"));
    }
}
