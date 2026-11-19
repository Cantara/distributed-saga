package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.file.queue.QueueFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A saga log in one queue file. Writes, truncates, reads and {@link #close()} of one log are serialised by the queue
 * file's monitor. {@code write} and {@code truncate} report every failure through the returned future; the reads
 * throw.
 */
class FileSagaLog implements SagaLog, AutoCloseable {

    private final FileSagaLogId sagaLogId;
    private final Path path;
    final FileSagaLogEntryCodec codec;
    private final QueueFile queueFile;
    private final FileSagaLogLock lock;

    // guarded by queueFile
    private long nextId;
    private long persistedNextId;
    private boolean closed;

    /**
     * Opens the log, holding an exclusive lock on its lock file ({@code <log file>.lock}) until {@link #close()}.
     *
     * @throws SagaLogBusyException if another process, or another open log in this process, holds the lock
     * @throws UncheckedIOException if the log cannot be opened or read
     */
    FileSagaLog(SagaLogId _sagaLogId) throws SagaLogBusyException {
        this.sagaLogId = (FileSagaLogId) _sagaLogId;
        this.path = sagaLogId.getPath();
        this.codec = new FileSagaLogEntryCodec(path);
        FileSagaLogLock fileLock = null;
        QueueFile file = null;
        try {
            boolean newFile = !Files.exists(path) || !Files.exists(lockPath(path));
            fileLock = FileSagaLogLock.acquire(path);
            file = new QueueFile.Builder(path.toFile()).build();
            if (newFile) {
                Directories.sync(path.getParent()); // the new file names are durable before the first entry is
            }
            // continue after the highest id in the file, and after every id handed out before the log was emptied
            long maxId = -1;
            for (byte[] bytes : file) {
                maxId = Math.max(maxId, codec.readId(bytes));
            }
            long kept = fileLock.readNextId();
            this.nextId = Math.max(maxId + 1, kept);
            this.persistedNextId = kept;
        } catch (Throwable t) {
            closeAfterFailure(t, file, fileLock);
            if (t instanceof IOException e) { // also thrown, undeclared, by the queue file's iterator
                throw new UncheckedIOException("Cannot open saga-log " + path, e);
            }
            if (t instanceof RuntimeException e) {
                throw e;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException("Cannot open saga-log " + path, t);
        }
        this.lock = fileLock;
        this.queueFile = file;
    }

    private static void closeAfterFailure(Throwable primary, AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    primary.addSuppressed(e);
                }
            }
        }
    }

    static Path lockPath(Path logPath) {
        return FileSagaLogLock.lockPath(logPath);
    }

    @Override
    public SagaLogId id() {
        return sagaLogId;
    }

    /**
     * Writes the entry, durably, before the returned future completes. The log assigns the id, unless the builder
     * already has one; an id given that way must be greater than every id in the log, or {@code truncate(id)} and the
     * ids assigned after a restart can go wrong. Ids assigned later follow it.
     */
    @Override
    public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
        synchronized (queueFile) {
            try {
                checkOpen();
                if (builder.id() == null) {
                    builder.id(new FileSagaLogEntryId(nextId));
                }
                SagaLogEntry entry = builder.build();
                queueFile.add(codec.serialize(entry));
                nextId = Math.max(nextId, ((FileSagaLogEntryId) entry.getId()).id() + 1);
                return CompletableFuture.completedFuture(entry);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot write to saga-log ", e));
            }
        }
    }

    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId id) {
        synchronized (queueFile) {
            try {
                checkOpen();
                if (!(id instanceof FileSagaLogEntryId fileId)) {
                    return CompletableFuture.completedFuture(null); // no entry of this log has it
                }
                long target = fileId.id();
                int n = 0;
                for (byte[] bytes : queueFile) {
                    n++;
                    if (codec.readId(bytes) == target) {
                        if (n == queueFile.size()) {
                            persistNextId(); // the log becomes empty
                        }
                        queueFile.remove(n);
                        break;
                    }
                }
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot truncate saga-log ", e));
            }
        }
    }

    @Override
    public CompletableFuture<Void> truncate() {
        synchronized (queueFile) {
            try {
                checkOpen();
                persistNextId();
                queueFile.clear();
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot truncate saga-log ", e));
            }
        }
    }

    /**
     * Keeps the next id in the lock file before the log is emptied, so that ids stay unique after a restart.
     */
    private void persistNextId() throws IOException {
        if (nextId != persistedNextId) {
            lock.writeNextId(nextId);
            persistedNextId = nextId;
        }
    }

    /**
     * @throws IllegalStateException if the log is closed
     * @throws UncheckedIOException  if the log cannot be read
     */
    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        return readAll().stream().map(codec::deserialize);
    }

    /**
     * @throws IllegalStateException if the log is closed
     * @throws UncheckedIOException  if the log cannot be read
     */
    @Override
    public Stream<SagaLogEntry> readEntries(String executionId) {
        byte[] wanted = executionId.getBytes(StandardCharsets.UTF_8);
        return readAll().stream()
                .filter(bytes -> codec.mayHaveExecutionId(bytes, wanted))
                .map(codec::deserialize)
                .filter(entry -> executionId.equals(entry.getExecutionId()));
    }

    /**
     * Copies every element under the monitor; they are decoded after it is released.
     */
    private List<byte[]> readAll() {
        List<byte[]> list = new ArrayList<>();
        synchronized (queueFile) {
            checkOpen();
            try {
                for (byte[] bytes : queueFile) {
                    list.add(bytes);
                }
            } catch (Exception e) {
                throw translate("Cannot read saga-log ", e);
            }
        }
        return list;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Saga-log is closed: " + path);
        }
    }

    /**
     * An {@link IOException}, which the queue file's iterator throws without declaring it, becomes an
     * {@link UncheckedIOException} naming the operation and the file; an unchecked exception stays as it is.
     */
    private RuntimeException translate(String operation, Exception e) {
        if (e instanceof IOException io) {
            return new UncheckedIOException(operation + path, io);
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException(operation + path, e);
    }

    @Override
    public String toString(SagaLogEntryId id) {
        return String.valueOf(((FileSagaLogEntryId) id).id());
    }

    @Override
    public SagaLogEntryId fromString(String idString) {
        return new FileSagaLogEntryId(Long.parseLong(idString));
    }

    @Override
    public byte[] toBytes(SagaLogEntryId id) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(((FileSagaLogEntryId) id).id());
        return bytes;
    }

    @Override
    public SagaLogEntryId fromBytes(byte[] idBytes) {
        return new FileSagaLogEntryId(ByteBuffer.wrap(idBytes).getLong());
    }

    /**
     * Closes the log and releases its lock. A second call does nothing. Waits for a running write, truncate or read of
     * this log. The first failure is thrown, with any later one suppressed.
     */
    @Override
    public void close() throws IOException {
        synchronized (queueFile) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                queueFile.close();
            } catch (Throwable t) {
                closeAfterFailure(t, lock);
                throw t;
            }
            lock.close();
        }
    }
}
