package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;

public class PostgresSagaLogPoolTest {

    PostgresSagaLogPool pool;

    private Map<String, String> configuration() {
        return Map.of(
                "cluster.owner", "sagalog",
                "cluster.name", "internal-sagalog-integration-testing",
                "cluster.instance-id", "01"
        );
    }

    private void cleanDatabase(Map<String, String> configuration, PostgresDataSource dataSource) throws SQLException {
        String schema = configuration.get("cluster.owner");
        String username = configuration.get("postgres.driver.user");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            connection.createStatement().executeUpdate(String.format("DROP SCHEMA IF EXISTS \"%s\" CASCADE", schema));
            connection.createStatement().executeUpdate(String.format("CREATE SCHEMA \"%s\" AUTHORIZATION \"%s\"", schema, username));
            PostgresSagaLogInitializer.createSchemaIfNotExists(connection, schema, username);
            PostgresSagaLogInitializer.createLocksTableIfNotExists(connection, schema);
        }
    }

    @BeforeMethod
    public void setup() {
        Map<String, String> configuration = configuration();
        EmbeddedPostgresSagaLogInitializer embeddedPostgresSagaLogInitializer = new EmbeddedPostgresSagaLogInitializer();
        pool = embeddedPostgresSagaLogInitializer.initialize(configuration);
        for (SagaLogId sagaLogId : pool.instanceLocalLogIds()) {
            try {
                SagaLog sagaLog = pool.connect(sagaLogId);
                sagaLog.truncate();
            } catch (SagaLogBusyException e) {
            }
        }
    }

    @AfterMethod
    public void teardown() {
        pool.shutdown();
    }

    @Test
    public void thatIdForHasCorrectHashcodeEquals() {
        assertEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "somelogid"));
        assertFalse(pool.idFor("A", "somelogid") == pool.idFor("A", "somelogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "otherlogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("B", "otherlogid"));
    }

    @Test
    void thatClusterWideLogIdsAreTheSameAsInstanceLocalLogIds() {
        SagaLogId l1 = pool.registerInstanceLocalIdFor("l1");
        pool.connect(l1);
        SagaLogId l2 = pool.registerInstanceLocalIdFor("l2");
        pool.connect(l2);
        SagaLogId x1 = pool.idFor("otherInstance", "x1");
        pool.connect(x1);
        assertEquals(pool.clusterWideLogIds(), Set.of(l1, l2, x1));
        assertEquals(pool.instanceLocalLogIds(), Set.of(l1, l2));
    }

    @Test
    void thatConnectExternalProducesANonNullSagaLog() {
        assertNotNull(pool.connectExternal(pool.registerInstanceLocalIdFor("anyId")));
    }

    @Test(expectedExceptions = SagaLogBusyException.class)
    void thatSagaLogCannotBeOpenedBySeparatePools() {
        SagaLogId sagaLogId = pool.registerInstanceLocalIdFor("somelog");
        pool.connect(sagaLogId);
        pool.connect(sagaLogId);
        Map<String, String> configuration = configuration();
        EmbeddedPostgresSagaLogInitializer embeddedPostgresSagaLogInitializer = new EmbeddedPostgresSagaLogInitializer();
        PostgresSagaLogPool anotherPool = embeddedPostgresSagaLogInitializer.initialize(configuration);
        pool.connect(sagaLogId);
        anotherPool.connect(sagaLogId);
    }
}
