package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;

@Test(groups = "integration")
public class PostgresSagaLogTest {

    private static final PostgresSagaLogId SAGA_LOG_ID = new PostgresSagaLogId("sagalog", "internal-sagalog-integration-testing", "01", "the-saga-log");

    private PostgresDataSource dataSource;
    private PostgresSagaLog sagaLog;

    @BeforeClass
    public void initializePostgresNamespace() throws SQLException {
        Map<String, String> configuration = Map.of(
                "cluster.owner", "sagalog",
                "cluster.name", "internal-sagalog-integration-testing",
                "cluster.instance-id", "01"
        );
        String username = "postgres";
        dataSource = new EmbeddedPostgresDataSource("postgres", username, "postgres");

        String schema = configuration.get("cluster.owner");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            connection.createStatement().executeUpdate(String.format("DROP SCHEMA IF EXISTS \"%s\" CASCADE", schema));
            connection.createStatement().executeUpdate(String.format("CREATE SCHEMA \"%s\" AUTHORIZATION \"%s\"", schema, username));
            PostgresSagaLogInitializer.createSchemaIfNotExists(connection, schema, username);
            PostgresSagaLogInitializer.createLocksTableIfNotExists(connection, schema);
        }
    }

    @AfterClass
    public void closePostgres() {
        // TODO
    }

    @BeforeMethod
    private void createAndCleanPostgresSagaLog() {
        sagaLog = new PostgresSagaLog(dataSource, SAGA_LOG_ID, new Random(), 1 /* To test re-connect path */);
        sagaLog.truncate().join();
    }

    @AfterMethod
    private void closePostgresSagaLog() {
        sagaLog.close();
    }

    @Test
    public void thatWriteAndReadEntriesWorks() {
        Deque<SagaLogEntry> expectedEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());

        assertEquals(sagaLog.readIncompleteSagas().collect(Collectors.toList()), expectedEntries);
    }

    @Test
    public void thatTruncateWithReadIncompleteWorks() {
        Deque<SagaLogEntry> initialEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        sagaLog.truncate(initialEntries.getLast().getId()).join();

        Deque<SagaLogEntry> expectedEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());

        List<SagaLogEntry> actualEntries = sagaLog.readIncompleteSagas().collect(Collectors.toList());
        assertEquals(actualEntries, expectedEntries);
    }

    @Test
    public void thatNoTruncateWithReadIncompleteWorks() {
        Deque<SagaLogEntry> firstEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        Deque<SagaLogEntry> secondEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        Deque<SagaLogEntry> expectedEntries = new LinkedList<>();
        expectedEntries.addAll(firstEntries);
        expectedEntries.addAll(secondEntries);

        List<SagaLogEntry> actualEntries = sagaLog.readIncompleteSagas().collect(Collectors.toList());
        assertEquals(actualEntries, expectedEntries);
    }

    @Test
    public void thatSnapshotOfSagaLogEntriesByNodeIdWorks() {
        Deque<SagaLogEntry> firstEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        Deque<SagaLogEntry> secondEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        Deque<SagaLogEntry> expectedEntries = new LinkedList<>();
        expectedEntries.addAll(firstEntries);
        expectedEntries.addAll(secondEntries);

        Map<String, List<SagaLogEntry>> snapshotFirst = sagaLog.getSnapshotOfSagaLogEntriesByNodeId(firstEntries.getFirst().getExecutionId());
        Set<SagaLogEntry> firstFlattenedSnapshot = new LinkedHashSet<>();
        for (List<SagaLogEntry> collection : snapshotFirst.values()) {
            firstFlattenedSnapshot.addAll(collection);
        }
        Map<String, List<SagaLogEntry>> snapshotSecond = sagaLog.getSnapshotOfSagaLogEntriesByNodeId(secondEntries.getFirst().getExecutionId());
        Set<SagaLogEntry> secondFlattenedSnapshot = new LinkedHashSet<>();
        for (List<SagaLogEntry> collection : snapshotSecond.values()) {
            secondFlattenedSnapshot.addAll(collection);
        }

        assertEquals(firstFlattenedSnapshot, Set.copyOf(firstEntries));
        assertEquals(secondFlattenedSnapshot, Set.copyOf(secondEntries));
    }

    @Test
    public void thatEntriesAreReadInIdOrderNotInPhysicalOrder() throws SQLException {
        Deque<SagaLogEntry> written = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());
        String executionId = written.getFirst().getExecutionId();
        // a row stored after the others, with an id that sorts before them: an id from 2020
        UUID earlier = new UUID((1577836800000L << 16) | 0x7000L, 0x8000_0000_0000_0001L);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (java.sql.PreparedStatement ps = connection.prepareStatement(String.format(
                    "INSERT INTO \"%s\".\"%s\" (txid, entry_id, entry_type, node_id, saga_name, data) VALUES(?, ?, ?, ?, NULL, NULL)",
                    SAGA_LOG_ID.getSchema(), SAGA_LOG_ID.getTableName()))) {
                ps.setString(1, executionId);
                ps.setObject(2, earlier);
                ps.setShort(3, PostgresSagaTools.toShort(no.cantara.sagalog.SagaLogEntryType.Start));
                ps.setString(4, "action0");
                ps.executeUpdate();
            }
        }
        List<SagaLogEntry> all = sagaLog.readIncompleteSagas().collect(Collectors.toList());
        assertEquals(all.size(), written.size() + 1);
        assertEquals(all.get(0).getNodeId(), "action0");
        assertEquals(all.subList(1, all.size()), List.copyOf(written));

        List<SagaLogEntry> ofExecution = sagaLog.readEntries(executionId).collect(Collectors.toList());
        assertEquals(ofExecution.get(0).getNodeId(), "action0");
        assertEquals(ofExecution.subList(1, ofExecution.size()), List.copyOf(written));
    }

    @Test
    public void thatEntryIdsAreUuidV7AndRoundTrip() {
        for (SagaLogEntry entry : writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString())) {
            UUID id = ((PostgresSagaLogEntryId) entry.getId()).id;
            assertEquals(id.version(), 7);
            assertEquals(id.variant(), 2);
            assertEquals(sagaLog.fromString(sagaLog.toString(entry.getId())), entry.getId());
            assertEquals(sagaLog.fromBytes(sagaLog.toBytes(entry.getId())), entry.getId());
            assertEquals(sagaLog.toString(entry.getId()), id.toString());
        }
    }

    private Deque<SagaLogEntry> writeSuccessfulVanillaSagaExecutionEntries(SagaLog sagaLog, String executionId) {
        Deque<SagaLogEntryBuilder> entryBuilders = new LinkedList<>();
        entryBuilders.add(sagaLog.builder().startSaga(executionId, "Vanilla-Saga", "{}"));
        entryBuilders.add(sagaLog.builder().startAction(executionId, "action1"));
        entryBuilders.add(sagaLog.builder().startAction(executionId, "action2"));
        entryBuilders.add(sagaLog.builder().endAction(executionId, "action1", "{}"));
        entryBuilders.add(sagaLog.builder().endAction(executionId, "action2", "{}"));
        entryBuilders.add(sagaLog.builder().endSaga(executionId));

        Deque<SagaLogEntry> entries = new LinkedList<>();
        for (SagaLogEntryBuilder builder : entryBuilders) {
            CompletableFuture<SagaLogEntry> entryFuture = sagaLog.write(builder);
            entries.add(entryFuture.join());
        }
        return entries;
    }
}
