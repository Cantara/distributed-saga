package no.cantara.sagalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One append-only saga log. A log is used by one owner at a time (see {@link SagaLogPool}), but the steps of one saga
 * execution run in parallel, so a provider must accept {@link #write} calls from several threads at once. Logs are
 * opened and closed by their pool.
 * <p>
 * The providers in this library (memory, file, PostgreSQL) do their work on the calling thread: {@code write} and
 * {@code truncate} return a future that is already complete, and the reads return once the entries are read. So every
 * call blocks its caller for the I/O; do not call a log from a thread that must not block (an event loop, a reactive
 * scheduler). Their calls run inside {@code synchronized} sections: on JDK 21 to 23 a virtual thread that calls them
 * pins its carrier thread for the I/O (JDK 24 removes this, JEP 491).
 */
public interface SagaLog extends AutoCloseable {

    /**
     * @return this log's id
     */
    SagaLogId id();

    /**
     * Appends an entry. The log assigns the entry's id when the builder has none.
     *
     * @param builder the entry to write; not null
     * @return a future of the written entry, with its id, completed when the entry is stored, or failed if it could
     * not be stored
     */
    CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder);

    /**
     * Removes the entries up to and including the entry with the given id, in write order.
     *
     * @param id the id of the last entry to remove; not null
     * @return a future completed when the entries are removed
     */
    CompletableFuture<Void> truncate(SagaLogEntryId id);

    /**
     * Removes every entry in this log.
     *
     * @return a future completed when the entries are removed
     */
    CompletableFuture<Void> truncate();

    /**
     * Reads every entry in this log, in the order the entries were written. The log holds the entries of every saga
     * written since it was last truncated: unfinished sagas, and also finished ones (with an End entry on node
     * {@code "E"}, written after success and after rollback) whose entries have not been truncated yet. Callers that need only unfinished sagas filter on that.
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

    /**
     * Writes an entry id of this log as text; {@link #fromString(String)} reads it back. Nothing in this library uses
     * the four id conversions; they are kept for applications that store entry ids.
     *
     * @param id an entry id of this log
     * @return the id as text
     */
    String toString(SagaLogEntryId id);

    /**
     * Reads an entry id written by {@link #toString(SagaLogEntryId)}.
     *
     * @param id the id as text
     * @return the entry id
     */
    SagaLogEntryId fromString(String id);

    /**
     * Writes an entry id of this log as bytes; {@link #fromBytes(byte[])} reads it back.
     *
     * @param id an entry id of this log
     * @return the id as bytes
     */
    byte[] toBytes(SagaLogEntryId id);

    /**
     * Reads an entry id written by {@link #toBytes(SagaLogEntryId)}.
     *
     * @param idBytes the id as bytes
     * @return the entry id
     */
    SagaLogEntryId fromBytes(byte[] idBytes);

    /**
     * @return a new entry builder
     */
    default SagaLogEntryBuilder builder() {
        return new SagaLogEntryBuilder();
    }

    /**
     * {@link #readEntries(String)} grouped by node id: each node's entries in write order, the nodes in the order of
     * their first entry. Only saga execution uses it; it is kept here for compatibility.
     *
     * @param executionId the saga execution id
     * @return the entries by node id
     */
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
