package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class HikariPostgresDataSource implements PostgresDataSource {

    private final HikariDataSource hikariDataSource;

    public HikariPostgresDataSource(HikariDataSource hikariDataSource) {
        this.hikariDataSource = hikariDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return hikariDataSource.getConnection();
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
