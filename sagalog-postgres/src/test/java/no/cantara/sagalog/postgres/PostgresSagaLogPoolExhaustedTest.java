package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.annotations.Test;

import java.sql.Connection;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Opening a saga log when every connection of the pool is held by an open log fails after one connection timeout,
 * not after a retry, and says what to change.
 */
public class PostgresSagaLogPoolExhaustedTest {

    @Test(groups = "integration")
    public void thatAnExhaustedPoolFailsOnceWithAClearMessage() throws Exception {
        EmbeddedPostgresDataSource embedded = new EmbeddedPostgresDataSource("postgres", "postgres", "postgres");
        // as PostgresSagaLogInitializer configures it, with a 1 s connection timeout (the default is 30 s)
        HikariConfig config = new HikariConfig();
        config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        config.addDataSourceProperty("serverName", "localhost");
        config.addDataSourceProperty("portNumber", embedded.getEmbeddedPostgres().getPort());
        config.addDataSourceProperty("user", "postgres");
        config.addDataSourceProperty("password", "postgres");
        config.addDataSourceProperty("databaseName", "postgres");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(1000);
        HikariDataSource hikari = new HikariDataSource(config);
        PostgresDataSource dataSource = new HikariPostgresDataSource(hikari);
        try {
            try (Connection connection = dataSource.getConnection()) {
                PostgresSagaLogInitializer.createSchemaIfNotExists(connection, "sagalog_exhausted", "postgres");
                PostgresSagaLogInitializer.createLocksTableIfNotExists(connection, "sagalog_exhausted");
                connection.commit();
            }
            PostgresSagaLogPool pool = new PostgresSagaLogPool(dataSource, "sagalog_exhausted", "ex", "01");
            pool.connect(pool.idFor("01", "a"));
            pool.connect(pool.idFor("01", "b"));

            long start = System.nanoTime();
            RuntimeException e = expectThrows(RuntimeException.class, () -> pool.connect(pool.idFor("01", "c")));
            long ms = (System.nanoTime() - start) / 1_000_000;

            assertTrue(ms < 1900, "waited " + ms + " ms: more than one connection timeout");
            assertTrue(e.getMessage().contains("the pool has 2 connections") && e.getMessage().contains("connection-pool.max-size"),
                    e.getMessage());
            pool.shutdown();
        } finally {
            hikari.close();
        }
    }
}
