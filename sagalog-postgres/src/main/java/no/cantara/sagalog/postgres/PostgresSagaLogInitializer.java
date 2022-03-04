package no.cantara.sagalog.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import no.cantara.sagalog.SagaLogInitializer;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

public class PostgresSagaLogInitializer implements SagaLogInitializer {

    public PostgresSagaLogInitializer() {
    }

    @Override
    public PostgresSagaLogPool initialize(Map<String, String> configuration) {
        String clusterOwner = configuration.get("cluster.owner");
        String namespace = configuration.get("cluster.name");
        String instanceId = configuration.get("cluster.instance-id");

        HikariDataSource hikariDataSource = createHikariDataSource(configuration);
        PostgresDataSource dataSource = new HikariPostgresDataSource(hikariDataSource);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.beginRequest();
            createSchemaIfNotExists(connection, clusterOwner, configuration.get("postgres.driver.user"));
            createLocksTableIfNotExists(connection, clusterOwner);
            connection.commit();
            connection.endRequest();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return new PostgresSagaLogPool(dataSource, clusterOwner, namespace, instanceId);

    }

    @Override
    public Map<String, String> configurationOptionsAndDefaults() {
        return Map.of(
                "cluster.owner", "mycompany",
                "cluster.name", "internal-sagalog-integration-testing",
                "cluster.instance-id", "01",
                "postgres.driver.host", "localhost",
                "postgres.driver.port", "5432",
                "postgres.driver.user", "test",
                "postgres.driver.password", "test",
                "postgres.driver.database", "sagalog",
                "connection-pool.max-size", "10"
        );
    }

    static HikariDataSource createHikariDataSource(Map<String, String> configuration) {
        boolean enableH2DatabaseDriver = Boolean.parseBoolean(configuration.get("h2.enabled"));

        HikariDataSource dataSource;

        int maxConnectionPoolSize = Integer.parseInt(configuration.getOrDefault("connection-pool.max-size", "10"));

        if (enableH2DatabaseDriver) {
            dataSource = openH2DataSource(
                    configuration.get("h2.driver.url"),
                    "sa",
                    "sa",
                    maxConnectionPoolSize
            );
        } else {
            dataSource = openPostgresDataSource(
                    configuration.get("postgres.driver.host"),
                    configuration.get("postgres.driver.port"),
                    configuration.get("postgres.driver.user"),
                    configuration.get("postgres.driver.password"),
                    configuration.get("postgres.driver.database"),
                    maxConnectionPoolSize
            );
        }
        return dataSource;
    }

    static HikariDataSource openPostgresDataSource(String postgresDbDriverHost, String postgresDbDriverPort, String postgresDbDriverUser, String postgresDbDriverPassword, String postgresDbDriverDatabase, int maxConnectionPoolSize) {
        Properties props = new Properties();
        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
        props.setProperty("dataSource.serverName", postgresDbDriverHost);
        props.setProperty("dataSource.portNumber", postgresDbDriverPort);
        props.setProperty("dataSource.user", postgresDbDriverUser);
        props.setProperty("dataSource.password", postgresDbDriverPassword);
        props.setProperty("dataSource.databaseName", postgresDbDriverDatabase);
        props.put("dataSource.logWriter", new PrintWriter(System.out));

        HikariConfig config = new HikariConfig(props);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(maxConnectionPoolSize);
        HikariDataSource datasource = new HikariDataSource(config);

        return datasource;
    }

    static void createSchemaIfNotExists(Connection connection, String schema, String username) throws SQLException {
        try (Statement st = connection.createStatement()) {
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"", schema, username);
            st.executeUpdate(sql);
        }
    }

    static void createLocksTableIfNotExists(Connection connection, String schema) throws SQLException {
        try (Statement st = connection.createStatement()) {
            String sql = String.format("CREATE TABLE IF NOT EXISTS \"%s\".\"Locks\" (\n" +
                    "    namespace       varchar NOT NULL,\n" +
                    "    instance_id     varchar NOT NULL,\n" +
                    "    log_id          varchar NOT NULL,\n" +
                    "    lock_key        bigint  NOT NULL,\n" +
                    "    PRIMARY KEY (namespace, instance_id, log_id)\n" +
                    ")", schema);
            st.executeUpdate(sql);
        }
    }

    static HikariDataSource openH2DataSource(String jdbcUrl, String username, String password, int maxConnectionPoolSize) {
        Properties props = new Properties();
        props.setProperty("jdbcUrl", jdbcUrl);
        props.setProperty("username", username);
        props.setProperty("password", password);
        props.put("dataSource.logWriter", new PrintWriter(System.out));

        HikariConfig config = new HikariConfig(props);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(maxConnectionPoolSize);
        HikariDataSource datasource = new HikariDataSource(config);

        return datasource;
    }
}
