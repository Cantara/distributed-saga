package no.cantara.sagalog.postgres;

import java.sql.Connection;
import java.sql.SQLException;

public interface PostgresDataSource extends AutoCloseable {

    Connection getConnection() throws SQLException;

    void evictConnection(Connection connection);

    @Override
    void close();
}
