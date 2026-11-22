package no.cantara.sagalog.postgres;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * {@code initialize} fails when the database will not give the pool all of its connections, instead of starting and
 * failing under load; and a pool that later cannot get a connection back says that the database refused it.
 * <p>
 * Runs its own server with {@code max_connections = 8}: the shared embedded server is left as it is.
 */
@Test(groups = "integration")
public class PostgresSagaLogStartCheckTest {

    private static final int MAX_CONNECTIONS = 8;

    private EmbeddedPostgres server;
    private final List<Connection> others = new ArrayList<>(); // other clients of the server

    @BeforeClass
    public void startServer() throws Exception {
        server = EmbeddedPostgres.builder().setServerConfig("max_connections", String.valueOf(MAX_CONNECTIONS)).start();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @AfterMethod(alwaysRun = true)
    public void closeOthers() throws Exception {
        for (Connection connection : others) {
            connection.close();
        }
        others.clear();
        awaitClientConnections(null, 0); // the next test starts on an idle server
    }

    private Map<String, String> configuration(int maxSize, String applicationName) {
        return configuration(maxSize, applicationName, "sagalog_start", "postgres");
    }

    private Map<String, String> configuration(int maxSize, String applicationName, String schema, String user) {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("cluster.owner", schema);
        configuration.put("cluster.name", "start");
        configuration.put("cluster.instance-id", "01");
        configuration.put("postgres.driver.host", "localhost");
        configuration.put("postgres.driver.port", String.valueOf(server.getPort()));
        configuration.put("postgres.driver.user", user);
        configuration.put("postgres.driver.password", "postgres");
        configuration.put("postgres.driver.database", "postgres");
        configuration.put("postgres.driver.property.ApplicationName", applicationName);
        configuration.put("connection-pool.max-size", String.valueOf(maxSize));
        configuration.put("connection-pool.connection-timeout", "1000");
        return configuration;
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = server.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Client connections, other than the one that counts them; of one application, or of all when it is null.
     */
    private long clientConnections(String applicationName) throws SQLException {
        try (Connection c = server.getPostgresDatabase().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'"
                     + " AND pid <> pg_backend_pid()"
                     + (applicationName == null ? "" : " AND application_name = '" + applicationName + "'"))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Other clients take connections until {@code free} are left on the server.
     */
    private void leaveFree(int free) throws SQLException {
        while (clientConnections(null) < MAX_CONNECTIONS - free) {
            others.add(server.getPostgresDatabase().getConnection());
        }
        assertEquals(clientConnections(null), MAX_CONNECTIONS - free, "connections in use");
    }

    private void awaitClientConnections(String applicationName, long expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (clientConnections(applicationName) != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(clientConnections(applicationName), expected, "connections of " + applicationName);
    }

    @Test
    public void thatAPoolThatStartsHoldsAllItsConnections() throws Exception {
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer().initializePool(configuration(4, "start-ok"));
        try {
            assertEquals(clientConnections("start-ok"), 4, "the pool's connections, held after the start");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatInitializeFailsWhenTheServerHasNoRoomForThePool() throws Exception {
        leaveFree(3);

        PostgresSagaLogException e = expectThrows(PostgresSagaLogException.class,
                () -> new PostgresSagaLogInitializer().initializePool(configuration(5, "start-full")));

        String message = e.getMessage();
        assertEquals(e.getOperation(), "initialize");
        assertEquals(e.getSqlState(), "53300", message); // too_many_connections
        assertTrue(message.contains("gave the pool 3 of its 5 connections (connection-pool.max-size)"), message);
        assertTrue(message.contains("connection-pool.connection-timeout"), message);
        assertTrue(message.contains("max_connections " + MAX_CONNECTIONS), message);
        assertTrue(message.contains("superuser_reserved_connections"), message);
        assertTrue(message.contains("too many clients"), message);
        awaitClientConnections("start-full", 0); // the pool was closed
    }

    @Test
    public void thatAPoolThatCannotGetAConnectionBackSaysTheDatabaseRefusedIt() throws Exception {
        // a CONNECTION LIMIT holds only for a role that is not a superuser
        execute("CREATE ROLE sagalog_later LOGIN");
        execute("GRANT CREATE ON DATABASE postgres TO sagalog_later");
        PostgresSagaLogPool pool = new PostgresSagaLogInitializer()
                .initializePool(configuration(3, "start-later", "sagalog_later", "sagalog_later"));
        try {
            execute("ALTER ROLE sagalog_later CONNECTION LIMIT 2"); // holds for new connections only
            Connection lost = pool.dataSource.getConnection();
            pool.dataSource.evictConnection(lost); // as when a connection is lost: the pool must open a new one
            awaitClientConnections("start-later", 2);

            Connection a = pool.dataSource.getConnection();
            Connection b = pool.dataSource.getConnection();
            SQLTransientConnectionException e = expectThrows(SQLTransientConnectionException.class, pool.dataSource::getConnection);
            a.close();
            b.close();

            assertTrue(e.getMessage().contains("the pool has only 2 of its 3 connections, because the database refused more"),
                    e.getMessage());
            assertTrue(e.getMessage().contains("too many connections for role"), e.getMessage());
        } finally {
            pool.shutdown();
        }
    }
}
