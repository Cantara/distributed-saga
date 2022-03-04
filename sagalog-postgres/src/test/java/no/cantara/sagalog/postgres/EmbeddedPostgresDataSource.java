package no.cantara.sagalog.postgres;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public class EmbeddedPostgresDataSource implements PostgresDataSource {

    private static class EmbeddedPostgresSingletonHolder {
        private static final EmbeddedPostgres embeddedPostgres;

        static {
            try {
                embeddedPostgres = EmbeddedPostgres.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    final EmbeddedPostgres embeddedPostgres;
    final String dbName;
    final String userName;
    final String password;

    public EmbeddedPostgresDataSource(String dbName, String userName, String password) {
        this.dbName = dbName;
        this.userName = userName;
        this.password = password;
        this.embeddedPostgres = EmbeddedPostgresSingletonHolder.embeddedPostgres;
    }

    public EmbeddedPostgres getEmbeddedPostgres() {
        return embeddedPostgres;
    }

    public DataSource getDataSource() {
        Map<String, String> properties = new LinkedHashMap<>();
        if (password != null && !password.trim().isEmpty()) {
            properties.put("password", this.password);
        }
        return embeddedPostgres.getDatabase(userName, dbName, properties);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    @Override
    public void evictConnection(Connection connection) {
        // do nothing
    }

    @Override
    public void close() {
        /*
         * Do not close anything, as the underlying embedded-postgres might be re-used in another test after this close.
         */
    }
}
