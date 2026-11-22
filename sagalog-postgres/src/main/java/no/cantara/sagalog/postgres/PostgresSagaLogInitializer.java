package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opens the PostgreSQL saga-log pool: a HikariCP pool, the schema and the {@code Locks} table (created if missing, and
 * migrated if made by an earlier version). See {@code docs/modules/sagalog-postgres.md} for the configuration keys.
 */
public class PostgresSagaLogInitializer implements SagaLogInitializer {

    /** Prefix of keys passed to the PostgreSQL driver as they are, for example {@code postgres.driver.property.ApplicationName}. */
    static final String DRIVER_PROPERTY_PREFIX = "postgres.driver.property.";

    static final String DEFAULT_CONNECTION_TIMEOUT_MS = "30000";
    // the defaults are the driver's and the server's own: no timeout and no keepalive unless the application sets one
    static final String DEFAULT_SOCKET_TIMEOUT_MS = "0";
    static final String DEFAULT_STATEMENT_TIMEOUT_MS = "0";
    static final String DEFAULT_LOCK_TIMEOUT_MS = "0";
    static final String DEFAULT_TCP_KEEP_ALIVE = "false";

    public PostgresSagaLogInitializer() {
    }

    /**
     * @throws IllegalArgumentException if a name is not valid (see {@link PostgresSagaLogId}) or a value is malformed
     * @throws PostgresSagaLogException if the schema or the Locks table cannot be made, or if the database does not give
     *                                  the pool all of its {@code connection-pool.max-size} connections (see
     *                                  {@link #checkThatThePoolGetsAllItsConnections})
     */
    @Override
    public SagaLogPool initialize(Map<String, String> configuration) {
        return initializePool(configuration);
    }

    /**
     * {@link #initialize(Map)} with the concrete type, for this package (the type is not part of the API).
     */
    PostgresSagaLogPool initializePool(Map<String, String> configuration) {
        String clusterOwner = configuration.get("cluster.owner");
        String namespace = configuration.get("cluster.name");
        String instanceId = configuration.get("cluster.instance-id");
        new PostgresSagaLogId(clusterOwner, namespace, instanceId, "x"); // before a pool is opened
        String user = PostgresSagaLogId.checkRoleName("postgres.driver.user", configuration.get("postgres.driver.user"));

        HikariDataSource hikariDataSource = createHikariDataSource(configuration);
        PostgresDataSource dataSource = new HikariPostgresDataSource(hikariDataSource);
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                createSchemaIfNotExists(connection, clusterOwner, user);
                createLocksTableIfNotExists(connection, clusterOwner);
                connection.commit();
            }
            checkThatThePoolGetsAllItsConnections(hikariDataSource);
        } catch (SQLException | RuntimeException e) {
            RuntimeException failure = e instanceof RuntimeException re ? re
                    : new PostgresSagaLogException("initialize", "schema " + clusterOwner, e);
            try {
                hikariDataSource.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return new PostgresSagaLogPool(dataSource, clusterOwner, namespace, instanceId);
    }

    @Override
    public Map<String, String> configurationOptionsAndDefaults() {
        return Map.ofEntries(
                Map.entry("cluster.owner", "mycompany"),
                Map.entry("cluster.name", "internal-sagalog-integration-testing"),
                Map.entry("cluster.instance-id", "01"),
                Map.entry("postgres.driver.host", "localhost"),
                Map.entry("postgres.driver.port", "5432"),
                Map.entry("postgres.driver.user", "test"),
                Map.entry("postgres.driver.password", "test"),
                Map.entry("postgres.driver.database", "sagalog"),
                Map.entry("postgres.driver.sslmode", "prefer"),
                Map.entry("postgres.driver.socket-timeout", DEFAULT_SOCKET_TIMEOUT_MS),
                Map.entry("postgres.driver.tcp-keep-alive", DEFAULT_TCP_KEEP_ALIVE),
                Map.entry("postgres.statement-timeout", DEFAULT_STATEMENT_TIMEOUT_MS),
                Map.entry("postgres.lock-timeout", DEFAULT_LOCK_TIMEOUT_MS),
                Map.entry("connection-pool.max-size", "10"),
                Map.entry("connection-pool.connection-timeout", DEFAULT_CONNECTION_TIMEOUT_MS)
        );
    }

    static HikariDataSource createHikariDataSource(Map<String, String> configuration) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(createPostgresDataSource(configuration));
        config.setAutoCommit(false);
        config.setMaximumPoolSize(intValue(configuration, "connection-pool.max-size", "10"));
        config.setConnectionTimeout(intValue(configuration, "connection-pool.connection-timeout", DEFAULT_CONNECTION_TIMEOUT_MS));
        return new HikariDataSource(config);
    }

    /**
     * The driver's data source: host, port, user, password and database; TLS ({@code sslmode}, {@code sslrootcert});
     * the socket timeout and TCP keepalive; {@code statement_timeout} and {@code lock_timeout} for every session; and
     * any {@code postgres.driver.property.<name>} as it is. A setting the application leaves out keeps the driver's or
     * the server's default: no timeouts, no keepalive, {@code sslmode=prefer}.
     * <p>
     * The PostgreSQL JDBC driver is found at run time, by class name, so that this module does not require it: the
     * driver jar is an automatic module, which jlink rejects. The application provides the driver, on the class path or
     * the module path. Reflection is used once per pool, when it is created.
     */
    static DataSource createPostgresDataSource(Map<String, String> configuration) {
        PgDataSource dataSource = PgDataSource.create();
        dataSource.call("setServerNames", String[].class, new String[]{configuration.getOrDefault("postgres.driver.host", "localhost")});
        dataSource.call("setPortNumbers", int[].class, new int[]{intValue(configuration, "postgres.driver.port", "5432")});
        dataSource.call("setUser", String.class, configuration.get("postgres.driver.user"));
        dataSource.call("setPassword", String.class, configuration.get("postgres.driver.password"));
        dataSource.call("setDatabaseName", String.class, configuration.get("postgres.driver.database"));
        String sslmode = configuration.get("postgres.driver.sslmode");
        if (sslmode != null) {
            dataSource.call("setSslMode", String.class, sslmode);
        }
        String sslrootcert = configuration.get("postgres.driver.sslrootcert");
        if (sslrootcert != null) {
            dataSource.call("setSslRootCert", String.class, sslrootcert);
        }
        int socketTimeoutMs = intValue(configuration, "postgres.driver.socket-timeout", DEFAULT_SOCKET_TIMEOUT_MS);
        dataSource.call("setSocketTimeout", int.class, (socketTimeoutMs + 999) / 1000); // the driver counts in seconds; 0 is none
        dataSource.call("setTcpKeepAlive", boolean.class, Boolean.parseBoolean(configuration.getOrDefault("postgres.driver.tcp-keep-alive", DEFAULT_TCP_KEEP_ALIVE)));
        int statementTimeoutMs = intValue(configuration, "postgres.statement-timeout", DEFAULT_STATEMENT_TIMEOUT_MS);
        int lockTimeoutMs = intValue(configuration, "postgres.lock-timeout", DEFAULT_LOCK_TIMEOUT_MS);
        StringBuilder sessionOptions = new StringBuilder();
        if (statementTimeoutMs > 0) {
            sessionOptions.append("-c statement_timeout=").append(statementTimeoutMs);
        }
        if (lockTimeoutMs > 0) {
            sessionOptions.append(sessionOptions.length() > 0 ? " " : "").append("-c lock_timeout=").append(lockTimeoutMs);
        }
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            if (entry.getKey().startsWith(DRIVER_PROPERTY_PREFIX)) {
                String name = entry.getKey().substring(DRIVER_PROPERTY_PREFIX.length());
                try {
                    dataSource.setProperty(name, entry.getValue());
                } catch (SQLException e) {
                    throw new IllegalArgumentException("Unknown PostgreSQL driver property in " + entry.getKey() + ": " + e.getMessage(), e);
                }
            }
        }
        if (sessionOptions.length() > 0) {
            String userOptions = (String) dataSource.call("getOptions", null, null);
            // options given as a driver property come last, so a -c setting there wins over the timeouts here
            dataSource.call("setOptions", String.class, userOptions == null || userOptions.isBlank() ? sessionOptions.toString() : sessionOptions + " " + userOptions);
        }
        return dataSource.dataSource;
    }

    /**
     * The driver's {@code PGSimpleDataSource}, configured through its public setters, looked up by name.
     */
    private static final class PgDataSource {
        static final String CLASS_NAME = "org.postgresql.ds.PGSimpleDataSource";

        final DataSource dataSource;

        private PgDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        static PgDataSource create() {
            Class<?> type;
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                type = Class.forName(CLASS_NAME, true, loader != null ? loader : PostgresSagaLogInitializer.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                try {
                    type = Class.forName(CLASS_NAME, true, PostgresSagaLogInitializer.class.getClassLoader());
                } catch (ClassNotFoundException again) {
                    e.addSuppressed(again);
                    throw new IllegalStateException("The PostgreSQL JDBC driver (org.postgresql:postgresql) is not on the "
                            + "class path or the module path: " + CLASS_NAME + " was not found", e);
                }
            }
            try {
                return new PgDataSource((DataSource) type.getConstructor().newInstance());
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IllegalStateException("Cannot create the PostgreSQL JDBC driver's " + CLASS_NAME, e);
            }
        }

        /**
         * Calls a public method with one argument, or none when {@code parameterType} is null.
         */
        Object call(String method, Class<?> parameterType, Object argument) {
            try {
                return parameterType == null
                        ? dataSource.getClass().getMethod(method).invoke(dataSource)
                        : dataSource.getClass().getMethod(method, parameterType).invoke(dataSource, argument);
            } catch (InvocationTargetException e) {
                throw unchecked(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The PostgreSQL JDBC driver's " + CLASS_NAME + " has no " + method
                        + ": use a driver of version 42.7 or later", e);
            }
        }

        /**
         * The driver's own failure, as it is: unchecked passes through, an {@code Error} is rethrown.
         */
        private static RuntimeException unchecked(Throwable t) {
            if (t instanceof RuntimeException e) {
                return e;
            }
            if (t instanceof Error e) {
                throw e;
            }
            return new IllegalStateException("The PostgreSQL JDBC driver's " + CLASS_NAME + " failed", t);
        }

        void setProperty(String name, String value) throws SQLException {
            try {
                dataSource.getClass().getMethod("setProperty", String.class, String.class).invoke(dataSource, name, value);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof SQLException sql) {
                    throw sql;
                }
                throw unchecked(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The PostgreSQL JDBC driver's " + CLASS_NAME + " has no setProperty", e);
            }
        }
    }

    /**
     * Takes every connection of the pool at once, then gives them all back. Each open saga log holds one connection for
     * as long as it is open, so a pool that cannot open all of its connections fails later, under load, when one log
     * too many is opened. HikariCP needs only one connection to start, and fills the rest of the pool in the background,
     * where a refusal (PostgreSQL's {@code too many clients}, a role's or database's {@code CONNECTION LIMIT}) is only
     * logged at DEBUG: this check makes it a start failure instead.
     * <p>
     * The check asks the server for the connections; it does not estimate them from {@code max_connections} and
     * {@code pg_stat_activity}, which other clients change at any time. The pool keeps the connections while it runs
     * (its minimum idle is its maximum size); only when HikariCP replaces one (after {@code maxLifetime}, or when one
     * fails) is a server slot free for a moment. The server state is read only to explain a failure.
     * <p>
     * A refusal for lack of connections (SQLState {@code 53300}) fails the check at once, not after the connection
     * timeout: while it waits, the check holds the server's last free connections, and every other client of the
     * server is refused too. Any other failure to open a connection (a network blip, a failover, a slow login) may pass,
     * so the check keeps waiting, and fails only when no connection has come for a whole connection timeout. A large
     * pool on a slow link therefore gets the time it needs.
     * <p>
     * The check proves that the server, or a connection pooler in front of it, accepted the connections. A pooler such
     * as PgBouncer opens its server connections later, so the check says nothing about the server behind it.
     *
     * @throws PostgresSagaLogException if the database refuses a connection for lack of connections, or no connection
     *                                  comes within the pool's connection timeout, with a message that gives the pool
     *                                  size, how many connections the pool got, the server's limits, and what to change
     */
    static void checkThatThePoolGetsAllItsConnections(HikariDataSource pool) {
        int maxSize = pool.getMaximumPoolSize();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(pool.getConnectionTimeout());
        List<Connection> held = new ArrayList<>(maxSize);
        try {
            while (held.size() < maxSize) {
                try {
                    held.add(borrow(pool, System.nanoTime() + timeoutNanos)); // a full timeout for each connection
                } catch (SQLException e) {
                    throw notEnoughConnections(pool, held, e);
                }
            }
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // a connection the pool cannot take back is evicted by the pool; the check's outcome stands
                }
            }
        }
    }

    /** How long one borrow of the check waits before it looks for a refusal again. */
    private static final long CHECK_STEP_MS = 250;

    /**
     * A connection from the pool. Fails as soon as the pool's last failure to open a connection is a refusal for lack
     * of connections (HikariCP gives the timeout exception that failure's SQLState), and otherwise at the deadline.
     */
    private static Connection borrow(HikariDataSource dataSource, long deadline) throws SQLException {
        if (!(dataSource.getHikariPoolMXBean() instanceof HikariPool pool)) {
            return dataSource.getConnection(); // not expected: then the check waits the whole connection timeout
        }
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            try {
                return pool.getConnection(Math.max(1, Math.min(CHECK_STEP_MS, left)));
            } catch (SQLTransientConnectionException e) {
                if (HikariPostgresDataSource.refusedForLackOfConnections(e) || left <= CHECK_STEP_MS) {
                    throw e; // refused, or out of time
                }
            }
        }
    }

    private static PostgresSagaLogException notEnoughConnections(HikariDataSource pool, List<Connection> held, SQLException e) {
        StringBuilder detail = new StringBuilder(String.format(
                "the database gave the pool %d of its %d connections (connection-pool.max-size)", held.size(), pool.getMaximumPoolSize()));
        Throwable lastFailure = e.getCause(); // HikariCP's last failure to open a connection, if any
        if (HikariPostgresDataSource.refusedForLackOfConnections(e)) {
            detail.append(" and refused more. Each open saga log holds one connection for as long as it is open, so the "
                    + "logs would fail under load. Lower connection-pool.max-size, or make room on the server: raise "
                    + "max_connections or the CONNECTION LIMIT, or close other clients' connections. Last refusal: ")
                    .append(rootMessage(lastFailure != null ? lastFailure : e)).append('.');
        } else {
            detail.append(String.format("; no further connection came within %d ms (connection-pool.connection-timeout). "
                    + "Each open saga log holds one connection for as long as it is open. Check that the database is "
                    + "reachable and accepts connections, or raise connection-pool.connection-timeout.", pool.getConnectionTimeout()));
            if (lastFailure != null) {
                detail.append(" Last failure to open a connection: ").append(rootMessage(lastFailure)).append('.');
            }
        }
        Exception readFailure = null;
        if (!held.isEmpty()) { // nearly always: the pool had a connection for the schema just before
            try {
                detail.append(' ').append(ServerConnections.read(held.get(0)));
            } catch (SQLException | RuntimeException r) {
                readFailure = r;
            }
        }
        PostgresSagaLogException failure = new PostgresSagaLogException("initialize", "the connection pool", detail.toString(), e);
        if (readFailure != null) {
            failure.addSuppressed(readFailure);
        }
        return failure;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /**
     * The server's connection limits and use, read to explain why the pool did not get its connections.
     */
    static final class ServerConnections {

        private ServerConnections() {
        }

        /*
         * reserved_connections exists from PostgreSQL 16; current_setting(name, true) is null on an older server.
         * A role without pg_read_all_stats sees the rows of other users' sessions with backend_type null, but with their
         * user and database: such a row with a database is counted as a client connection.
         */
        private static final String SQL = """
                WITH clients AS (
                    SELECT usename, datname FROM pg_stat_activity
                    WHERE backend_type = 'client backend' OR (backend_type IS NULL AND datid IS NOT NULL))
                SELECT current_setting('max_connections')::int,
                       current_setting('superuser_reserved_connections')::int,
                       coalesce(current_setting('reserved_connections', true), '0')::int,
                       (SELECT count(*) FROM clients),
                       current_user,
                       (SELECT rolconnlimit FROM pg_roles WHERE rolname = current_user),
                       (SELECT count(*) FROM clients WHERE usename = current_user),
                       current_database(),
                       (SELECT datconnlimit FROM pg_database WHERE datname = current_database()),
                       (SELECT count(*) FROM clients WHERE datname = current_database())
                """;

        static String read(Connection connection) throws SQLException {
            String result;
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(SQL)) {
                rs.next();
                StringBuilder s = new StringBuilder(String.format(
                        "Server: max_connections %d, superuser_reserved_connections %d, reserved_connections %d, "
                                + "%d client connections in use",
                        rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                int roleLimit = rs.getInt(6);
                if (roleLimit >= 0) {
                    s.append(String.format("; role %s has CONNECTION LIMIT %d, %d in use", rs.getString(5), roleLimit, rs.getLong(7)));
                }
                int databaseLimit = rs.getInt(9);
                if (databaseLimit >= 0) {
                    s.append(String.format("; database %s has CONNECTION LIMIT %d, %d in use", rs.getString(8), databaseLimit, rs.getLong(10)));
                }
                result = s.append('.').toString();
            } catch (SQLException | RuntimeException e) {
                try {
                    endTransaction(connection);
                } catch (SQLException | RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure); // the query's failure is the one to report
                }
                throw e;
            }
            endTransaction(connection);
            return result;
        }

        /* the pool's connections do not auto-commit; one that does has no transaction to end */
        private static void endTransaction(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        }
    }

    private static int intValue(Map<String, String> configuration, String key, String defaultValue) {
        String value = configuration.getOrDefault(key, defaultValue);
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " cannot be negative: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a whole number: " + value, e);
        }
    }

    // java:S2077: only identifiers are formatted into this SQL, from PostgresSagaLogId: checked against a strict
    // pattern and quoted (sp-6). Values are bound parameters.
    @SuppressWarnings("java:S2077")
    static void createSchemaIfNotExists(Connection connection, String schema, String username) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE SCHEMA IF NOT EXISTS " + PostgresSagaLogId.quote(schema)
                    + " AUTHORIZATION " + PostgresSagaLogId.quote(username));
        }
    }

    /**
     * Creates the Locks table, or adds the {@code owner_token} column to one made by 2.1.0 or earlier.
     */
    // java:S2077: only identifiers are formatted into this SQL, from PostgresSagaLogId: checked against a strict
    // pattern and quoted (sp-6). Values are bound parameters.
    @SuppressWarnings("java:S2077")
    static void createLocksTableIfNotExists(Connection connection, String schema) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        namespace       varchar NOT NULL,
                        instance_id     varchar NOT NULL,
                        log_id          varchar NOT NULL,
                        lock_key        bigint  NOT NULL,
                        owner_token     bigint  NOT NULL DEFAULT 0,
                        PRIMARY KEY (namespace, instance_id, log_id)
                    )""".formatted(PostgresSagaLogId.locksTable(schema)));
        }
        LockRegistry.migrate(connection, schema);
    }
}
