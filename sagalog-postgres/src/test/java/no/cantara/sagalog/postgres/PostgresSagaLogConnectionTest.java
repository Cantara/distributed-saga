package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogOwner;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;

/**
 * A saga log gives its connection back when it is closed, and leaves no transaction open. Recovering other instances'
 * logs (take ownership, read, truncate, release, remove, delete), as an application does on every recovery pass, must
 * not use up connections.
 */
public class PostgresSagaLogConnectionTest {

    private static final String SCHEMA = "sagalog_connections";

    private final EmbeddedPostgresDataSource admin = new EmbeddedPostgresDataSource("postgres", "postgres", "postgres");
    private PostgresSagaLogPool pool;

    @BeforeMethod
    public void setUp() throws SQLException {
        try (Connection connection = admin.getConnection(); Statement st = connection.createStatement()) {
            connection.setAutoCommit(true);
            st.executeUpdate("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
        pool = new EmbeddedPostgresSagaLogInitializer().initialize(Map.of(
                "cluster.owner", SCHEMA,
                "cluster.name", "connections",
                "cluster.instance-id", "02"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        pool.shutdown();
    }

    /**
     * @return the other backends of the test database by state, e.g. {idle=3, idle in transaction=1}
     */
    private Map<String, Long> backendsByState() throws SQLException {
        try (Connection connection = admin.getConnection(); Statement st = connection.createStatement()) {
            connection.setAutoCommit(true);
            try (ResultSet rs = st.executeQuery("SELECT coalesce(state, '') FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'")) {
                Map<String, Long> byState = new java.util.TreeMap<>();
                while (rs.next()) {
                    byState.merge(rs.getString(1), 1L, Long::sum);
                }
                return byState;
            }
        }
    }

    private long total(Map<String, Long> byState) {
        return byState.values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<String, Long> settled() throws Exception {
        // a closed connection's backend can take a moment to leave pg_stat_activity
        Map<String, Long> byState = backendsByState();
        for (int i = 0; i < 50 && byState.containsKey("idle in transaction"); i++) {
            Thread.sleep(20);
            byState = backendsByState();
        }
        return byState;
    }

    @Test
    public void thatRecoveringOtherInstancesLogsDoesNotKeepConnections() throws Exception {
        // logs of departed instances 10..13, five each, with one entry
        for (int instance = 10; instance <= 13; instance++) {
            for (int log = 0; log < 5; log++) {
                SagaLogId id = pool.idFor(String.valueOf(instance), "log-" + log);
                SagaLog sagaLog = pool.connect(id);
                sagaLog.write(sagaLog.builder().startSaga("exec-" + instance + "-" + log, "saga", "{}")).join();
                pool.remove(id);
                pool.delete(id);
            }
        }
        long before = total(settled());

        SagaLogOwner owner = new SagaLogOwner("recovery");
        for (int pass = 0; pass < 3; pass++) {
            for (SagaLogId id : pool.clusterWideLogIds()) {
                SagaLog sagaLog = pool.tryTakeOwnership(owner, id);
                sagaLog.readIncompleteSagas().collect(Collectors.toList());
                sagaLog.truncate().join();
                pool.releaseOwnership(id);
                pool.remove(id);
                pool.delete(id);
            }
        }

        Map<String, Long> after = settled();
        assertEquals(after.getOrDefault("idle in transaction", 0L), Long.valueOf(0), "transactions left open: " + after);
        assertEquals(total(after), before, "connections kept after 3 recovery passes over 20 logs: " + after);
    }

    @Test
    public void thatALogConnectedAgainAfterRemoveIsOpen() {
        SagaLogId id = pool.idFor("02", "reconnected");
        SagaLog first = pool.connect(id);
        first.write(first.builder().startSaga("exec-1", "saga", "{}")).join();
        pool.remove(id); // closes the log

        SagaLog again = pool.connect(id);
        again.write(again.builder().startSaga("exec-2", "saga", "{}")).join(); // threw "already closed" before
        assertEquals(again.readIncompleteSagas().count(), 2L);
    }

    @Test
    public void thatEveryEntryTypeIsReadBack() {
        SagaLogId id = pool.idFor("02", "entry-types");
        SagaLog log = pool.connect(id);
        for (no.cantara.sagalog.SagaLogEntryType type : no.cantara.sagalog.SagaLogEntryType.values()) {
            log.write(new no.cantara.sagalog.SagaLogEntryBuilder().executionId("e1").entryType(type).nodeId("n")).join();
        }
        assertEquals(log.readIncompleteSagas().map(no.cantara.sagalog.SagaLogEntry::getEntryType).collect(Collectors.toList()),
                java.util.List.of(no.cantara.sagalog.SagaLogEntryType.values()));
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = admin.getConnection(); Statement st = connection.createStatement()) {
            connection.setAutoCommit(true);
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    public void thatDeleteDropsTheTableAndItsLock() throws Exception {
        SagaLogId id = pool.idFor("10", "departed");
        SagaLog log = pool.connect(id);
        log.write(new no.cantara.sagalog.SagaLogEntryBuilder().startSaga("e1", "saga", "{}")).join();
        assertEquals(count("SELECT count(*) FROM pg_tables WHERE schemaname = '" + SCHEMA + "' AND tablename = 'SAGALOG_connections_10_departed'"), 1L);

        pool.remove(id);
        assertEquals(pool.delete(id), true);
        assertEquals(count("SELECT count(*) FROM pg_tables WHERE schemaname = '" + SCHEMA + "' AND tablename = 'SAGALOG_connections_10_departed'"), 0L);
        assertEquals(count("SELECT count(*) FROM \"" + SCHEMA + "\".\"Locks\" WHERE instance_id = '10' AND log_id = 'departed'"), 0L);
        assertEquals(pool.clusterWideLogIds().contains(id), false, "a deleted log is no longer listed");
        assertEquals(pool.delete(id), false, "deleting a log that does not exist");

        // the log can be used again; it starts empty
        SagaLog again = pool.connect(id);
        assertEquals(again.readIncompleteSagas().count(), 0L);
    }

    @Test
    public void thatALogOpenInAnotherPoolIsNotDeleted() throws Exception {
        PostgresSagaLogPool other = new EmbeddedPostgresSagaLogInitializer().initialize(Map.of(
                "cluster.owner", SCHEMA, "cluster.name", "connections", "cluster.instance-id", "03"));
        try {
            SagaLogId id = pool.idFor("03", "in-use");
            other.connect(id).write(new no.cantara.sagalog.SagaLogEntryBuilder().startSaga("e1", "saga", "{}")).join();
            org.testng.Assert.expectThrows(no.cantara.sagalog.SagaLogBusyException.class, () -> pool.delete(id));
            assertEquals(other.connect(id).readIncompleteSagas().count(), 1L, "the log in use is untouched");
        } finally {
            other.shutdown();
        }
    }
}
