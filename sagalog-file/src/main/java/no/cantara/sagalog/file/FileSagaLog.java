package no.cantara.sagalog.file;

import com.squareup.tape2.QueueFile;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class FileSagaLog implements SagaLog, AutoCloseable {

    private final FileSagaLogId sagaLogId;
    private final AtomicLong nextId = new AtomicLong(0);
    private final QueueFile queueFile;

    public FileSagaLog(SagaLogId _sagaLogId) {
        this.sagaLogId = (FileSagaLogId) _sagaLogId;
        Path path = sagaLogId.getPath();
        try {
            queueFile = new QueueFile.Builder(path.toFile()).build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SagaLogId id() {
        return sagaLogId;
    }

    @Override
    public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
        synchronized (queueFile) {
            if (builder.id() == null) {
                builder.id(new FileSagaLogEntryId(nextId.getAndIncrement()));
            }
            SagaLogEntry entry = builder.build();
            try {
                queueFile.add(serialize(entry));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(entry);
        }
    }

    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId id) {
        synchronized (queueFile) {
            Iterator<byte[]> iterator = queueFile.iterator();
            int n = 0;
            while (iterator.hasNext()) {
                SagaLogEntry entry = deserialize(iterator.next());
                n++;
                if (id.equals(entry.getId())) {
                    try {
                        queueFile.remove(n);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return CompletableFuture.completedFuture(null);
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> truncate() {
        synchronized (queueFile) {
            try {
                queueFile.clear();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        List<byte[]> list = new ArrayList<>();
        synchronized (queueFile) {
            for (byte[] bytes : queueFile) {
                list.add(bytes);
            }
        }
        return list.stream().map(this::deserialize);
    }

    @Override
    public Stream<SagaLogEntry> readEntries(String executionId) {
        return readIncompleteSagas().filter(entry -> executionId.equals(entry.getExecutionId()));
    }

    @Override
    public String toString(SagaLogEntryId id) {
        return String.valueOf(((FileSagaLogEntryId) id).id);
    }

    @Override
    public SagaLogEntryId fromString(String idString) {
        return new FileSagaLogEntryId(Long.parseLong(idString));
    }

    @Override
    public byte[] toBytes(SagaLogEntryId id) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(((FileSagaLogEntryId) id).id);
        return bytes;
    }

    @Override
    public SagaLogEntryId fromBytes(byte[] idBytes) {
        return new FileSagaLogEntryId(ByteBuffer.wrap(idBytes).getLong());
    }

    @Override
    public void close() throws IOException {
        queueFile.close();
    }

    byte[] serialize(SagaLogEntry entry) {
        String serializedString = toString(entry.getId())
                + " " + entry.getExecutionId()
                + " " + entry.getEntryType()
                + " " + entry.getNodeId()
                + (entry.getSagaName() == null ? "" : " " + entry.getSagaName())
                + (entry.getJsonData() == null ? "" : " " + entry.getJsonData());
        return serializedString.getBytes(StandardCharsets.UTF_8);
    }

    SagaLogEntry deserialize(byte[] bytes) {
        String serialized = new String(bytes, StandardCharsets.UTF_8);
        SagaLogEntryBuilder builder = builder();

        // mandatory log-fields

        int idEndIndex = serialized.indexOf(' ');
        String id = serialized.substring(0, idEndIndex);
        serialized = serialized.substring(idEndIndex + 1);

        builder.id(fromString(id));

        int executionIdEndIndex = serialized.indexOf(' ');
        String executionId = serialized.substring(0, executionIdEndIndex);
        serialized = serialized.substring(executionIdEndIndex + 1);

        builder.executionId(executionId);

        int entryTypeEndIndex = serialized.indexOf(' ');
        SagaLogEntryType entryType = SagaLogEntryType.valueOf(serialized.substring(0, entryTypeEndIndex));
        serialized = serialized.substring(entryTypeEndIndex + 1);

        builder.entryType(entryType);

        int nodeIdEndIdex = serialized.indexOf(' ');
        if (nodeIdEndIdex == -1) {
            return builder.nodeId(serialized).build();
        }

        String nodeId = serialized.substring(0, nodeIdEndIdex);
        serialized = serialized.substring(nodeIdEndIdex + 1);

        builder.nodeId(nodeId);

        // optional log-fields
        if ("S".equals(nodeId)) {
            int jsonDataBeginIndex = serialized.indexOf('{');
            if (jsonDataBeginIndex == -1) {
                String sagaName = serialized.substring(0, serialized.length() - 1);
                return builder.sagaName(sagaName).build();
            }
            String sagaName = serialized.substring(0, jsonDataBeginIndex - 1);
            String jsonData = serialized.substring(jsonDataBeginIndex);
            return builder.sagaName(sagaName).jsonData(jsonData).build();
        }

        int jsonDataBeginIndex = serialized.indexOf('{');
        if (jsonDataBeginIndex == -1) {
            return builder.build();
        }
        String jsonData = serialized.substring(jsonDataBeginIndex);
        return builder.jsonData(jsonData).build();
    }
}
