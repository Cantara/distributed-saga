package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;

public class FileSagaLogTest {

    FileSagaLog sagaLog;

    @BeforeMethod
    public void recreateSagaLog() {
        sagaLog = createNewSagaLog();
    }

    FileSagaLog createNewSagaLog() {
        FileSagaLogId logId = new FileSagaLogId(Paths.get("target"), "01", "mylog");
        Path path = logId.getPath();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new FileSagaLog(logId);
    }

    @Test
    public void thatWriteAndReadEntriesWorks() {
        Deque<SagaLogEntry> expectedEntries = writeSuccessfulVanillaSagaExecutionEntries(sagaLog, UUID.randomUUID().toString());

        assertEquals(sagaLog.readEntries(expectedEntries.getFirst().getExecutionId()).collect(Collectors.toList()), expectedEntries);
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

    @Test
    public void thatSerializationAndDeserializationWorks() {
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().startSaga("ex-1234", "Some-test-saga", "{}"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().startSaga("ex-1234", "Saga Name With Spaces", "{}"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().endSaga("ex-1234"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().startAction("ex-1234", "abc-Start-Action"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().endAction("ex-1234", "abc-End-Action", "{}"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().abort("ex-1234", "abc-Abort"));
        checkSerializationAndDeserialization(sagaLog, sagaLog.builder().compDone("ex-1234", "abc-Comp-Done"));
    }

    private void checkSerializationAndDeserialization(FileSagaLog sagaLog, SagaLogEntryBuilder builder) {
        SagaLogEntry input = sagaLog.write(builder).join();
        byte[] serializedInput = sagaLog.serialize(input);
        SagaLogEntry output = sagaLog.deserialize(serializedInput);
        assertEquals(output, input);
        byte[] serializedOutput = sagaLog.serialize(output);
        assertEquals(serializedOutput, serializedInput);
    }

}
