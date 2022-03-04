package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogInitializer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public class EmbeddedPostgresSagaLogInitializer implements SagaLogInitializer {

    public EmbeddedPostgresSagaLogInitializer() {
    }

    @Override
    public PostgresSagaLogPool initialize(Map<String, String> configuration) {
        String clusterOwner = configuration.get("cluster.owner");
        String namespace = configuration.get("cluster.name");
        String instanceId = configuration.get("cluster.instance-id");

        PostgresDataSource dataSource = new EmbeddedPostgresDataSource("postgres", "postgres", "postgres");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.beginRequest();
            createSchemaIfNotExists(connection, clusterOwner, "postgres");
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
                "connection-pool.max-size", "10"
        );
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
}
