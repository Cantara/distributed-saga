package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

public class HikariPostgresDataSource implements PostgresDataSource {

    private final HikariDataSource hikariDataSource;

    public HikariPostgresDataSource(HikariDataSource hikariDataSource) {
        this.hikariDataSource = hikariDataSource;
    }

    /**
     * @throws SQLTransientConnectionException if no connection was available within the pool's connection timeout,
     *                                         with a message that names the pool size; each open saga log holds one
     */
    @Override
    public Connection getConnection() throws SQLException {
        try {
            return hikariDataSource.getConnection();
        } catch (SQLTransientConnectionException e) {
            int max = hikariDataSource.getMaximumPoolSize();
            var pool = hikariDataSource.getHikariPoolMXBean();
            String usage = pool == null ? "" : String.format(" (%d active, %d idle)", pool.getActiveConnections(), pool.getIdleConnections());
            throw new SQLTransientConnectionException(String.format("No database connection available within %d ms: "
                    + "the pool has %d connections%s, and each open saga log holds one. Raise connection-pool.max-size "
                    + "or open fewer saga logs at once.", hikariDataSource.getConnectionTimeout(), max, usage), e.getSQLState(), e);
        }
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
