package no.cantara.sagalog.memory;


import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogId;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class MemorySagaLog implements SagaLog {

    private final SagaLogId sagaLogId;
    private final AtomicLong nextId = new AtomicLong(0);
    private final Deque<SagaLogEntry> incompleteEntries = new ConcurrentLinkedDeque<>();

    MemorySagaLog(SagaLogId sagaLogId) {
        this.sagaLogId = sagaLogId;
    }

    @Override
    public SagaLogId id() {
        return sagaLogId;
    }

    @Override
    public Stream<SagaLogEntry> readEntries(String executionId) {
        return incompleteEntries.stream().filter(e -> executionId.equals(e.getExecutionId()));
    }

    @Override
    public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
        synchronized (this) {
            if (builder.id() == null) {
                builder.id(new MemorySagaLogEntryId(nextId.getAndIncrement()));
            }
            SagaLogEntry entry = builder.build();
            incompleteEntries.add(entry);
            return CompletableFuture.completedFuture(entry);
        }
    }

    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId entryId) {
        Set<SagaLogEntry> toBeRemoved = new LinkedHashSet<>();
        Set toBeRemovedIds = new LinkedHashSet<>();
        for (SagaLogEntry sle : incompleteEntries) {
            toBeRemoved.add(sle);
            toBeRemovedIds.add(sle.getId());
            if (entryId.equals(sle.getId())) {
                break;
            }
        }
        if (toBeRemovedIds.contains(entryId)) {
            incompleteEntries.removeAll(toBeRemoved);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> truncate() {
        SagaLogEntry lastEntry = incompleteEntries.peekLast();
        if (lastEntry == null) {
            return CompletableFuture.completedFuture(null);
        }
        return truncate(lastEntry.getId());
    }

    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        return incompleteEntries.stream();
    }

    @Override
    public String toString(SagaLogEntryId id) {
        return String.valueOf(((MemorySagaLogEntryId) id).id);
    }

    @Override
    public SagaLogEntryId fromString(String idString) {
        return new MemorySagaLogEntryId(Long.parseLong(idString));
    }

    @Override
    public byte[] toBytes(SagaLogEntryId id) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(((MemorySagaLogEntryId) id).id);
        return bytes;
    }

    @Override
    public SagaLogEntryId fromBytes(byte[] idBytes) {
        return new MemorySagaLogEntryId(ByteBuffer.wrap(idBytes).getLong());
    }

    @Override
    public void close() {
        incompleteEntries.clear();
    }
}
