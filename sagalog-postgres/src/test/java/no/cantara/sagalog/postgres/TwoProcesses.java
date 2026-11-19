package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database faults for the provider's own tests, on the shared embedded PostgreSQL: a HikariCP pool per simulated
 * process, an admin session that terminates backends ({@code pg_terminate_backend}, as a failover or a network reset
 * would), and a third session that holds table locks (a slow database).
 */
final class TwoProcesses {

    private TwoProcesses() {
    }

    static int port() {
        return new EmbeddedPostgresDataSource("postgres", "postgres", "postgres").getEmbeddedPostgres().getPort();
    }

    static HikariDataSource hikari(int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        config.addDataSourceProperty("serverName", "localhost");
        config.addDataSourceProperty("portNumber", port());
        config.addDataSourceProperty("user", "postgres");
        config.addDataSourceProperty("password", "postgres");
        config.addDataSourceProperty("databaseName", "postgres");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(maxPoolSize);
        config.setConnectionTimeout(2000);
        return new HikariDataSource(config);
    }

    static Connection admin() throws SQLException {
        Connection connection = new EmbeddedPostgresDataSource("postgres", "postgres", "postgres").getConnection();
        connection.setAutoCommit(true);
        return connection;
    }

    static void execute(String sql) throws SQLException {
        try (Connection connection = admin(); Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    static List<String> query(String sql) throws SQLException {
        try (Connection connection = admin(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static void createSchema(String schema) throws SQLException {
        execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        try (Connection connection = admin()) {
            PostgresSagaLogInitializer.createSchemaIfNotExists(connection, schema, "postgres");
            PostgresSagaLogInitializer.createLocksTableIfNotExists(connection, schema);
        }
    }

    /**
     * A saga-log pool on its own HikariCP pool: one simulated process. A reconnect waits 1 s for the lock (10 s in production).
     */
    static PostgresSagaLogPool process(String schema, String instanceId, int maxPoolSize) {
        return new PostgresSagaLogPool(new HikariPostgresDataSource(hikari(maxPoolSize)), schema, "ns", instanceId, java.time.Duration.ofSeconds(1));
    }

    static long lockKey(String schema, String instanceId, String logName) throws SQLException {
        try (Connection connection = admin(); PreparedStatement ps = connection.prepareStatement(
                "SELECT lock_key FROM \"" + schema + "\".\"Locks\" WHERE instance_id = ? AND log_id = ?")) {
            ps.setString(1, instanceId);
            ps.setString(2, logName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no lock key for " + instanceId + "/" + logName);
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * @return the backends that hold the advisory lock with this key
     */
    static List<String> holdersOf(long key) throws SQLException {
        return query("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted AND objid = " + (key & 0xFFFFFFFFL)
                + " AND classid = " + (key >>> 32));
    }

    /**
     * Ends the session that holds the log's advisory lock, as a failover, a network reset or an idle-session kill
     * would, and waits until the server has released the lock.
     */
    static void loseSessionOf(long key) throws Exception {
        query("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND objid = " + (key & 0xFFFFFFFFL)
                + " AND classid = " + (key >>> 32));
        awaitNoHolderOf(key);
    }

    static void awaitNoHolderOf(long key) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!holdersOf(key).isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("advisory lock " + key + " still held by " + holdersOf(key));
            }
            Thread.sleep(20);
        }
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
