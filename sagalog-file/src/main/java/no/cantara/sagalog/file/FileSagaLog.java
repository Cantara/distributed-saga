package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.file.queue.QueueFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final FileChannel lockChannel;
    private final FileLock lock;

    /**
     * Opens the log, holding an exclusive lock on its lock file ({@code <log file>.lock}) until {@link #close()}.
     *
     * @throws SagaLogBusyException if another process, or another open log in this process, holds the lock
     */
    public FileSagaLog(SagaLogId _sagaLogId) throws SagaLogBusyException {
        this.sagaLogId = (FileSagaLogId) _sagaLogId;
        Path path = sagaLogId.getPath();
        FileChannel channel = null;
        FileLock fileLock = null;
        QueueFile file = null;
        try {
            channel = FileChannel.open(lockPath(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                fileLock = null; // held by another open log in this process
            }
            if (fileLock == null) {
                throw new SagaLogBusyException("Saga-log is in use by another process or another open log: " + path);
            }
            file = new QueueFile.Builder(path.toFile()).build();
            // continue after the highest id in the file, so ids stay unique across restarts
            long maxId = -1;
            for (byte[] bytes : file) {
                maxId = Math.max(maxId, ((FileSagaLogEntryId) deserialize(bytes).getId()).id);
            }
            nextId.set(maxId + 1);
        } catch (IOException e) {
            closeQuietly(file, fileLock, channel);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            closeQuietly(file, fileLock, channel);
            throw e;
        }
        this.lockChannel = channel;
        this.lock = fileLock;
        this.queueFile = file;
    }

    static Path lockPath(Path logPath) {
        return logPath.resolveSibling(logPath.getFileName() + ".lock");
    }

    private static void closeQuietly(QueueFile file, FileLock fileLock, FileChannel channel) {
        try {
            if (file != null) file.close();
        } catch (IOException ignored) {
        }
        try {
            if (fileLock != null) fileLock.release();
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
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
        try {
            queueFile.close();
        } finally {
            try {
                lock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    /**
     * Format version of an entry record; the first byte of every queue element.
     */
    static final byte FORMAT_VERSION = 2;

    /**
     * An entry as one queue element: the format version, the id, then executionId, entry type, nodeId, sagaName and
     * data, each as a length-prefixed UTF-8 string (length -1 for null). Any text is stored as it is.
     */
    byte[] serialize(SagaLogEntry entry) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            out.writeLong(((FileSagaLogEntryId) entry.getId()).id);
            writeString(out, entry.getExecutionId());
            writeString(out, entry.getEntryType().name());
            writeString(out, entry.getNodeId());
            writeString(out, entry.getSagaName());
            writeString(out, entry.getJsonData());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    SagaLogEntry deserialize(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(String.format("Saga-log %s holds an entry in an unsupported format "
                        + "(version byte %d, expected %d). Logs written before 2.0.1 cannot be read: let their sagas "
                        + "finish with the old version, or delete the log file.", sagaLogId.getPath(), version, FORMAT_VERSION));
            }
            SagaLogEntryBuilder builder = builder();
            builder.id(new FileSagaLogEntryId(in.readLong()));
            builder.executionId(readString(in));
            builder.entryType(SagaLogEntryType.valueOf(readString(in)));
            builder.nodeId(readString(in));
            builder.sagaName(readString(in));
            builder.jsonData(readString(in));
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt saga-log entry in " + sagaLogId.getPath(), e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
