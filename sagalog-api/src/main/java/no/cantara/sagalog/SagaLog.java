package no.cantara.sagalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface SagaLog extends AutoCloseable {

    SagaLogId id();

    CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder);

    CompletableFuture<Void> truncate(SagaLogEntryId id);

    CompletableFuture<Void> truncate();

    Stream<SagaLogEntry> readIncompleteSagas();

    Stream<SagaLogEntry> readEntries(String executionId);

    String toString(SagaLogEntryId id);

    SagaLogEntryId fromString(String id);

    byte[] toBytes(SagaLogEntryId id);

    SagaLogEntryId fromBytes(byte[] idBytes);

    default SagaLogEntryBuilder builder() {
        return new SagaLogEntryBuilder();
    }

    default Map<String, List<SagaLogEntry>> getSnapshotOfSagaLogEntriesByNodeId(String executionId) {
        Map<String, List<SagaLogEntry>> recoverySagaLogEntriesBySagaNodeId = new LinkedHashMap<>();
        List<SagaLogEntry> entries = readEntries(executionId).collect(Collectors.toList());
        for (SagaLogEntry entry : entries) {
            List<SagaLogEntry> nodeEntries = recoverySagaLogEntriesBySagaNodeId.get(entry.getNodeId());
            if (nodeEntries == null) {
                nodeEntries = new ArrayList<>(4);
                recoverySagaLogEntriesBySagaNodeId.put(entry.getNodeId(), nodeEntries);
            }
            nodeEntries.add(entry);
        }
        return recoverySagaLogEntriesBySagaNodeId;
    }
}
