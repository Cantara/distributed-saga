package no.cantara.sagalog.postgres;

import java.sql.Connection;
import java.sql.SQLException;

interface PostgresDataSource extends AutoCloseable {

    Connection getConnection() throws SQLException;

    /**
     * Ends the connection's session and does not give the connection back to the pool, so any advisory lock it holds
     * is released by the server. The caller does not close it afterwards.
     */
    void evictConnection(Connection connection);

    @Override
    void close();
}
