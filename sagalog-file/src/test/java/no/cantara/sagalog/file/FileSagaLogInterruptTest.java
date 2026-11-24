package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * The caller's interrupt must not break the log: a write or truncate on an interrupted thread completes, keeps the log's
 * lock, and leaves the thread interrupted. A log that has lost its lock all the same refuses every later operation, so
 * it never writes a file another process may hold.
 * <p>
 * The lock is held through a {@link FileChannel}, and the JDK closes a {@code FileChannel} when the thread using it is
 * interrupted; closing it releases the operating-system lock.
 */
public class FileSagaLogInterruptTest {

    final Path folder = Paths.get("target/sagalog-interrupt").toAbsolutePath();

    @BeforeMethod
    public void clean() throws Exception {
        if (Files.exists(folder)) {
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(folder);
    }

    @AfterMethod(alwaysRun = true)
    public void clearInterrupt() {
        Thread.interrupted();
    }

    FileSagaLogId id(String name) {
        return new FileSagaLogId(folder, "01", name);
    }

    static SagaLogEntryBuilder start(String executionId, String data) {
        return new SagaLogEntryBuilder().startSaga(executionId, "s", data);
    }

    /**
     * Whether this process still holds the lock on the log's lock file: a second channel in the same JVM gets an
     * {@link OverlappingFileLockException} while it does, and the lock once it is released.
     */
    static boolean lockHeld(FileSagaLogId id) throws Exception {
        Path lockFile = FileSagaLogLock.lockPath(id.getPath());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    return true; // held by another process
                }
                lock.release();
                return false;
            } catch (OverlappingFileLockException e) {
                return true;
            }
        }
    }

    @Test
    public void thatATruncateThatEmptiesTheLogOnAnInterruptedThreadKeepsTheLock() throws Exception {
        FileSagaLogId id = id("truncate");
        try (FileSagaLog log = new FileSagaLog(id)) {
            SagaLogEntry entry = log.write(start("e1", "{}")).join();

            Thread.currentThread().interrupt();
            log.truncate(entry.getId()).join(); // the log becomes empty: the next id is written to the lock file
            assertTrue(Thread.interrupted(), "the thread is still interrupted");

            assertTrue(lockHeld(id), "the log still holds its lock");
            log.write(start("e2", "{}")).join();
        }
    }

    @Test
    public void thatATruncateOfAllOnAnInterruptedThreadKeepsTheLock() throws Exception {
        FileSagaLogId id = id("truncate-all");
        try (FileSagaLog log = new FileSagaLog(id)) {
            log.write(start("e1", "{}")).join();

            Thread.currentThread().interrupt();
            log.truncate().join();
            assertTrue(Thread.interrupted(), "the thread is still interrupted");

            assertTrue(lockHeld(id), "the log still holds its lock");
            log.write(start("e2", "{}")).join();
        }
    }

    @Test
    public void thatAWriteThatGrowsTheFileOnAnInterruptedThreadCompletes() throws Exception {
        FileSagaLogId id = id("grow");
        String large = "{\"data\":\"" + "x".repeat(8192) + "\"}"; // larger than a new queue file: it grows
        try (FileSagaLog log = new FileSagaLog(id)) {
            Thread.currentThread().interrupt();
            SagaLogEntry entry = log.write(start("e1", large)).join();
            assertTrue(Thread.interrupted(), "the thread is still interrupted");

            assertEquals(log.readEntries("e1").count(), 1L);
            assertTrue(lockHeld(id), "the log still holds its lock");
            log.truncate(entry.getId()).join();
        }
        try (FileSagaLog reopened = new FileSagaLog(id)) {
            assertEquals(reopened.readIncompleteSagas().count(), 0L, "the file is intact");
        }
    }

    @Test
    public void thatALogThatHasLostItsLockRefusesEveryLaterOperation() throws Exception {
        FileSagaLogId id = id("lost");
        FileSagaLog log = new FileSagaLog(id);
        SagaLogEntry entry = log.write(start("e1", "{}")).join();
        lockChannel(log).close(); // as an interrupt during the lock file's write would: the lock is released
        assertFalse(lockHeld(id), "the lock is gone");

        CompletionException truncate = expectThrows(CompletionException.class, () -> log.truncate(entry.getId()).join());
        assertTrue(truncate.getCause() instanceof SagaLogBusyException, "the truncate reports the lost lock: " + truncate.getCause());
        CompletionException write = expectThrows(CompletionException.class, () -> log.write(start("e2", "{}")).join());
        assertTrue(write.getCause() instanceof SagaLogBusyException, "a later write is refused: " + write.getCause());
        expectThrows(SagaLogBusyException.class, () -> log.readEntries("e1").count());

        log.close(); // nothing to release; the registry forgets the lock file
        try (FileSagaLog reopened = new FileSagaLog(id)) {
            assertEquals(reopened.readEntries("e1").count(), 1L, "the log can be opened again, and kept its entry");
        } catch (SagaLogBusyException e) {
            fail("the lock file is still registered as held after close", e);
        }
    }

    @Test
    public void thatALogThatCannotReadItsFileAgainCanStillBeClosedAndOpenedAgain() throws Exception {
        FileSagaLogId id = id("unreadable");
        FileSagaLog log = new FileSagaLog(id);
        log.write(start("e1", "{}")).join();
        queueFileRaf(log).close(); // as an interrupt during the queue file's I/O would: the write and the reload fail

        expectThrows(CompletionException.class, () -> log.write(start("e2", "{}")).join());
        IllegalStateException refused = expectThrows(IllegalStateException.class, () -> log.readEntries("e1").count());
        assertTrue(refused.getMessage().contains("open it again"), "the log refuses every later operation: " + refused.getMessage());
        assertTrue(lockHeld(id), "the log still holds its lock until it is closed");

        log.close();
        assertFalse(lockHeld(id), "close released the lock");
        try (FileSagaLog reopened = new FileSagaLog(id)) {
            assertEquals(reopened.readEntries("e1").count(), 1L, "the log can be opened again, and kept its entry");
        } catch (SagaLogBusyException e) {
            fail("close did not release the lock file", e);
        }
    }

    private static java.io.RandomAccessFile queueFileRaf(FileSagaLog log) throws Exception {
        Field queueFileField = FileSagaLog.class.getDeclaredField("queueFile");
        queueFileField.setAccessible(true);
        Object queueFile = queueFileField.get(log);
        Field rafField = queueFile.getClass().getDeclaredField("raf");
        rafField.setAccessible(true);
        return (java.io.RandomAccessFile) rafField.get(queueFile);
    }

    private static FileChannel lockChannel(FileSagaLog log) throws Exception {
        Field lockField = FileSagaLog.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        Object lock = lockField.get(log);
        Field channelField = FileSagaLogLock.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        return (FileChannel) channelField.get(lock);
    }
}
