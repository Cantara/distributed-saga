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

    /**
     * Reads every entry in this log, in the order the entries were written. The log holds the entries of every saga
     * written since it was last truncated: unfinished sagas, and also finished ones (with an End entry on the start
     * node) whose entries have not been truncated yet. Callers that need only unfinished sagas filter on that.
     *
     * @return the entries, in write order
     */
    Stream<SagaLogEntry> readIncompleteSagas();

    /**
     * Reads the entries of one saga execution, in the order they were written.
     *
     * @param executionId the saga execution id
     * @return the entries, in write order
     */
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
