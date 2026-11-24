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
 * <p>
 * The caller's interrupt does not break the log. The JDK closes a file channel when the thread using it is
 * interrupted, and closing the lock file's channel releases the lock, so {@code write} and {@code truncate} set a
 * pending interrupt aside while they use the files and restore it before they return. A log whose lock is lost all the
 * same (an interrupt that arrives during the I/O) refuses every later operation with {@link SagaLogBusyException}, so it
 * never writes a file another process may have opened since: close it, and open it again.
 * <p>
 * The file is read once, when the log is opened; the decoded entries are kept in memory, and reads and truncates use
 * them. Only this open log can change the file, so the copy stays exact. Memory use follows the log's size, which
 * stays small when finished executions are truncated.
 */
class FileSagaLog implements SagaLog, AutoCloseable {

    private final FileSagaLogId sagaLogId;
    private final Path path;
    final FileSagaLogEntryCodec codec;
    private final QueueFile queueFile;
    private final FileSagaLogLock lock;

    // guarded by queueFile
    /**
     * The entries in the file, in file order, decoded once. Only this open log can change the file (the lock and the
     * process-wide registry guarantee one open log per file), so reads and truncates use this copy instead of reading
     * the file. It changes only after the file has.
     */
    private final ArrayList<SagaLogEntry> entries = new ArrayList<>();
    private long nextId;
    private long persistedNextId;
    private boolean closed;
    private boolean unreadable; // the file could not be read again after a failed change; close() still releases all
    private boolean lockLost;

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
                SagaLogEntry entry = codec.deserialize(bytes);
                entries.add(entry);
                maxId = Math.max(maxId, ((FileSagaLogEntryId) entry.getId()).id());
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
            boolean interrupted = Thread.interrupted(); // set aside: an interrupt closes the file channels
            try {
                checkOpen();
                if (builder.id() == null) {
                    builder.id(new FileSagaLogEntryId(nextId));
                }
                SagaLogEntry entry = builder.build();
                byte[] record = codec.serialize(entry);
                try {
                    queueFile.add(record);
                } catch (Exception e) {
                    reloadAfterFailure(e);
                    throw e;
                }
                entries.add(entry);
                nextId = Math.max(nextId, ((FileSagaLogEntryId) entry.getId()).id() + 1);
                return CompletableFuture.completedFuture(entry);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot write to saga-log ", e));
            } finally {
                restore(interrupted);
            }
        }
    }

    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId id) {
        synchronized (queueFile) {
            boolean interrupted = Thread.interrupted(); // set aside: an interrupt closes the file channels
            try {
                checkOpen();
                if (!(id instanceof FileSagaLogEntryId fileId)) {
                    return CompletableFuture.completedFuture(null); // no entry of this log has it
                }
                long target = fileId.id();
                int n = 0;
                int size = entries.size();
                while (n < size && ((FileSagaLogEntryId) entries.get(n).getId()).id() != target) {
                    n++;
                }
                if (n == size) {
                    return CompletableFuture.completedFuture(null); // no entry with this id
                }
                int count = n + 1;
                if (count == size) {
                    persistNextId(); // the log becomes empty
                }
                try {
                    queueFile.remove(count);
                } catch (Exception e) {
                    reloadAfterFailure(e);
                    throw e;
                }
                entries.subList(0, count).clear();
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot truncate saga-log ", e));
            } finally {
                restore(interrupted);
            }
        }
    }

    @Override
    public CompletableFuture<Void> truncate() {
        synchronized (queueFile) {
            boolean interrupted = Thread.interrupted(); // set aside: an interrupt closes the file channels
            try {
                checkOpen();
                persistNextId();
                try {
                    queueFile.clear();
                } catch (Exception e) {
                    reloadAfterFailure(e);
                    throw e;
                }
                entries.clear();
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(translate("Cannot truncate saga-log ", e));
            } finally {
                restore(interrupted);
            }
        }
    }

    /**
     * Restores the interrupt that was set aside, so that the caller sees it.
     */
    private static void restore(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * After a failed change of the file, the in-memory copy may no longer match it: read the file again. If that fails
     * too, the log can't be trusted: it refuses every later operation, and {@link #close()} still releases the file and
     * the lock, so the caller can open it again.
     */
    private void reloadAfterFailure(Exception primary) {
        try {
            ArrayList<SagaLogEntry> reloaded = new ArrayList<>(queueFile.size());
            for (byte[] bytes : queueFile) {
                reloaded.add(codec.deserialize(bytes));
            }
            entries.clear();
            entries.addAll(reloaded);
        } catch (Exception e) {
            primary.addSuppressed(e);
            unreadable = true;
            entries.clear();
        }
    }

    /**
     * Keeps the next id in the lock file before the log is emptied, so that ids stay unique after a restart.
     */
    private void persistNextId() throws IOException {
        if (nextId != persistedNextId) {
            try {
                lock.writeNextId(nextId);
            } catch (IOException e) {
                if (!lock.isValid()) {
                    throw lockLost(e); // the channel was closed (an interrupt during the write): the lock went with it
                }
                throw e;
            }
            persistedNextId = nextId;
        }
    }

    private SagaLogBusyException lockLost(Throwable cause) {
        lockLost = true;
        return new SagaLogBusyException("Saga-log lost its lock, so another process may have opened it; it refuses every"
                + " operation: close it, and open it again: " + path, cause);
    }

    /**
     * Reads the in-memory copy; no file I/O.
     *
     * @throws IllegalStateException if the log is closed, or could not read its file again after a failed change
     * @throws SagaLogBusyException  if the log has lost its lock
     */
    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        return snapshot().stream();
    }

    /**
     * Reads the in-memory copy; no file I/O.
     *
     * @throws IllegalStateException if the log is closed, or could not read its file again after a failed change
     * @throws SagaLogBusyException  if the log has lost its lock
     */
    @Override
    public Stream<SagaLogEntry> readEntries(String executionId) {
        List<SagaLogEntry> matching = new ArrayList<>();
        synchronized (queueFile) {
            checkOpen();
            for (int i = 0, size = entries.size(); i < size; i++) {
                SagaLogEntry entry = entries.get(i);
                if (executionId.equals(entry.getExecutionId())) {
                    matching.add(entry);
                }
            }
        }
        return matching.stream();
    }

    /**
     * A copy of the entries, taken under the monitor; entries are immutable, so only the references are copied.
     */
    private List<SagaLogEntry> snapshot() {
        synchronized (queueFile) {
            checkOpen();
            return new ArrayList<>(entries);
        }
    }

    /**
     * @throws IllegalStateException if the log is closed, or could not read its file again after a failed change
     * @throws SagaLogBusyException  if the log has lost its lock
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Saga-log is closed: " + path);
        }
        if (unreadable) {
            throw new IllegalStateException("Saga-log could not read its file again after a failed change, so it refuses"
                    + " every operation: close it, and open it again: " + path);
        }
        if (lockLost || !lock.isValid()) {
            throw lockLost(null);
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
            entries.clear();
            entries.trimToSize();
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
