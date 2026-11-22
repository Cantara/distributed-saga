package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

final class HikariPostgresDataSource implements PostgresDataSource {

    private final HikariDataSource hikariDataSource;

    HikariPostgresDataSource(HikariDataSource hikariDataSource) {
        this.hikariDataSource = hikariDataSource;
    }

    /** PostgreSQL's too_many_connections: max_connections, or a role's or database's CONNECTION LIMIT, is used up. */
    static final String TOO_MANY_CONNECTIONS = "53300";

    /**
     * @return true if the failure is the database refusing a connection for lack of connections. HikariCP gives its
     * timeout exception the SQLState of its last failure to open a connection.
     */
    static boolean refusedForLackOfConnections(SQLException e) {
        return TOO_MANY_CONNECTIONS.equals(e.getSQLState());
    }

    /**
     * @throws SQLTransientConnectionException if no connection was available within the pool's connection timeout,
     *                                         with a message that says why: every connection is in use (each open saga
     *                                         log holds one), the database refused more for lack of connections, or the
     *                                         pool could not open more for another reason
     */
    @Override
    public Connection getConnection() throws SQLException {
        try {
            return hikariDataSource.getConnection();
        } catch (SQLTransientConnectionException e) {
            throw explained(e);
        }
    }

    private SQLTransientConnectionException explained(SQLTransientConnectionException e) {
        long timeout = hikariDataSource.getConnectionTimeout();
        int max = hikariDataSource.getMaximumPoolSize();
        var pool = hikariDataSource.getHikariPoolMXBean();
        if (pool != null && pool.getTotalConnections() < max && e.getCause() != null) {
            // the pool is short of its size and could not open more: a larger pool would not help
            String why = refusedForLackOfConnections(e)
                    ? "because the database refused more (%s). Each open saga log holds one. Make room on the server "
                    + "(max_connections, the CONNECTION LIMIT, other clients), or lower connection-pool.max-size and open "
                    + "fewer saga logs at once."
                    : "and could not open more (%s). Each open saga log holds one. Check that the database is reachable "
                    + "and accepts connections.";
            return new SQLTransientConnectionException(String.format("No database connection available within %d ms: "
                            + "the pool has only %d of its %d connections, " + why, timeout, pool.getTotalConnections(), max,
                    e.getCause().getMessage()), e.getSQLState(), e);
        }
        String usage = pool == null ? "" : String.format(" (%d active, %d idle)", pool.getActiveConnections(), pool.getIdleConnections());
        return new SQLTransientConnectionException(String.format("No database connection available within %d ms: "
                + "the pool has %d connections%s, and each open saga log holds one. Raise connection-pool.max-size "
                + "or open fewer saga logs at once.", timeout, max, usage), e.getSQLState(), e);
    }

    @Override
    public void evictConnection(Connection connection) {
        hikariDataSource.evictConnection(connection);
    }

    @Override
    public void close() {
        hikariDataSource.close();
    }
}
