package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogBusyException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exclusive lock on a saga-log's lock file ({@code <log file>.lock}), held from {@link #acquire} until
 * {@link #close()}.
 * <p>
 * The operating-system lock belongs to the process: on POSIX systems, closing <em>any</em> descriptor of the lock file
 * in this process releases it. So this process never opens a second descriptor on a lock file it holds: a registry of
 * the lock files held in this process (by this class loader) refuses a second acquire before the file is touched.
 * <p>
 * The lock file also keeps the log's next entry id once the log has been emptied (see {@link #readNextId()}), so that
 * ids stay unique after a restart. A lock file written before this record existed is empty, which reads as no record.
 */
final class FileSagaLogLock implements AutoCloseable {

    /**
     * The lock files held in this process, by real path. Process-wide because the operating-system lock is.
     */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    /**
     * "SLID": the start of the next-id record.
     */
    private static final int NEXT_ID_MAGIC = 0x534C4944;
    private static final int NEXT_ID_RECORD_SIZE = 4 + 8;

    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;

    private FileSagaLogLock(Path lockFile, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.channel = channel;
        this.lock = lock;
    }

    static Path lockPath(Path logPath) {
        return logPath.resolveSibling(logPath.getFileName() + ".lock");
    }

    /**
     * Takes the lock of the log at {@code logPath}, creating the lock file if it is missing.
     *
     * @throws SagaLogBusyException if another process, or another open log or delete in this process, holds it
     * @throws IOException          if the lock file cannot be opened, for example because the folder is missing
     */
    static FileSagaLogLock acquire(Path logPath) throws SagaLogBusyException, IOException {
        Path lockFile = logPath.getParent().toRealPath().resolve(logPath.getFileName() + ".lock");
        if (!HELD.add(lockFile)) {
            throw new SagaLogBusyException("Saga-log is in use by another open log in this process: " + logPath);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held in this JVM outside the registry: a copy of this class in another class loader
            }
            if (lock == null) {
                throw new SagaLogBusyException("Saga-log is in use by another process: " + logPath);
            }
            return new FileSagaLogLock(lockFile, channel, lock);
        } catch (Throwable t) {
            try {
                if (channel != null) {
                    channel.close(); // this process holds no lock on the file: the registry said so
                }
            } catch (IOException e) {
                t.addSuppressed(e);
            } finally {
                HELD.remove(lockFile);
            }
            throw t;
        }
    }

    /**
     * @return the lock file
     */
    Path file() {
        return lockFile;
    }

    /**
     * @return the next entry id kept by {@link #writeNextId(long)}, or -1 when there is none
     * @throws IOException if the lock file cannot be read or holds something else
     */
    long readNextId() throws IOException {
        long size = channel.size();
        if (size == 0) {
            return -1;
        }
        ByteBuffer record = ByteBuffer.allocate(NEXT_ID_RECORD_SIZE);
        while (record.hasRemaining()) {
            if (channel.read(record, record.position()) < 0) {
                break;
            }
        }
        if (size != NEXT_ID_RECORD_SIZE || record.hasRemaining() || record.getInt(0) != NEXT_ID_MAGIC) {
            throw new IOException("Unexpected content in the saga-log lock file " + lockFile
                    + " (it keeps the next entry id). Delete the lock file if no process has the log open.");
        }
        return record.getLong(4);
    }

    /**
     * Keeps the next entry id, durably, in the lock file.
     */
    void writeNextId(long nextId) throws IOException {
        ByteBuffer record = ByteBuffer.allocate(NEXT_ID_RECORD_SIZE).putInt(NEXT_ID_MAGIC).putLong(nextId).flip();
        while (record.hasRemaining()) {
            channel.write(record, record.position());
        }
        channel.force(false);
    }

    /**
     * Releases the lock. Call once.
     */
    @Override
    public void close() throws IOException {
        try (channel) {
            lock.release();
        } finally {
            HELD.remove(lockFile);
        }
    }
}
