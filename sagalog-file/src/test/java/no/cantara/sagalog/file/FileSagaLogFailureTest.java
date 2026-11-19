package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Failures: I/O errors, corrupt records, use after close, and how they reach the caller.
 */
public class FileSagaLogFailureTest {

    /**
     * Offset of the first element's data in a new queue file: the 32-byte header, then the 4-byte element length.
     */
    static final int FIRST_ELEMENT = 32 + 4;

    final Path folder = Paths.get("target/sagalog-failure").toAbsolutePath();

    @BeforeMethod
    public void clean() throws Exception {
        if (Files.exists(folder)) {
            folder.toFile().setWritable(true);
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(folder);
    }

    FileSagaLogId id(String name) {
        return new FileSagaLogId(folder, "01", name);
    }

    static SagaLogEntryBuilder start(String executionId) {
        return new SagaLogEntryBuilder().startSaga(executionId, "s", "{}");
    }

    static void overwriteInt(Path file, long position, int value) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(position);
            raf.writeInt(value);
        }
    }

    // sf-4: an element length that no longer fits the file is an I/O error while reading

    @Test
    public void thatAnIOErrorWhileReadingIsAnUncheckedIOExceptionNamingTheFile() throws Exception {
        FileSagaLogId id = id("io");
        try (FileSagaLog log = new FileSagaLog(id)) {
            SagaLogEntry entry = log.write(start("e1")).join();
            overwriteInt(id.getPath(), 32, 100_000);
            try {
                log.readIncompleteSagas().toList();
                throw new AssertionError("no exception");
            } catch (UncheckedIOException e) {
                assertTrue(e.getMessage().contains(id.getPath().toString()), e.getMessage());
            } catch (Throwable t) {
                throw new AssertionError("expected UncheckedIOException, got " + t, t);
            }
            CompletableFuture<Void> truncated = log.truncate(entry.getId());
            CompletionException e = expectThrows(CompletionException.class, truncated::join);
            assertTrue(e.getCause() instanceof UncheckedIOException, String.valueOf(e.getCause()));
        }
    }

    // sf-12: a corrupt length or entry type inside a record is a corrupt entry

    @Test
    public void thatANegativeStringLengthIsACorruptEntry() throws Exception {
        FileSagaLogId id = id("neg");
        try (FileSagaLog log = new FileSagaLog(id)) {
            log.write(start("e1")).join();
            overwriteInt(id.getPath(), FIRST_ELEMENT + 1 + 8, -5); // the execution id's length
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> log.readIncompleteSagas().toList());
            assertTrue(e.getMessage().contains("Corrupt saga-log entry"), e.getMessage());
        }
    }

    @Test
    public void thatAStringLengthBeyondTheRecordIsACorruptEntry() throws Exception {
        FileSagaLogId id = id("big");
        try (FileSagaLog log = new FileSagaLog(id)) {
            log.write(start("e1")).join();
            overwriteInt(id.getPath(), FIRST_ELEMENT + 1 + 8, 1_000_000);
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> log.readIncompleteSagas().toList());
            assertTrue(e.getCause().getMessage().contains("length"), String.valueOf(e.getCause()));
        }
    }

    @Test
    public void thatAnUnknownEntryTypeIsACorruptEntry() throws Exception {
        FileSagaLogId id = id("type");
        try (FileSagaLog log = new FileSagaLog(id)) {
            log.write(start("e1")).join();
            // version, id, then "e1" (length and 2 bytes), then the entry type's length and its first byte
            try (RandomAccessFile raf = new RandomAccessFile(id.getPath().toFile(), "rw")) {
                raf.seek(FIRST_ELEMENT + 1 + 8 + 4 + 2 + 4);
                raf.writeByte('X');
            }
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> log.readIncompleteSagas().toList());
            assertTrue(e.getMessage().contains("Corrupt saga-log entry"), e.getMessage());
        }
    }

    // sf-6: close is idempotent; use after close is a clear failure

    @Test
    public void thatCloseTwiceIsANoOp() throws Exception {
        FileSagaLog log = new FileSagaLog(id("twice"));
        log.close();
        log.close();
        new FileSagaLog(id("twice")).close(); // and the lock is free
    }

    @Test
    public void thatShutdownAfterTheCallerClosedAPooledLogWorks() throws Exception {
        FileSagaLogPool pool = new FileSagaLogPool(folder, "01");
        SagaLog log = pool.connect(pool.idFor("01", "pooled"));
        log.close();
        pool.shutdown();
    }

    // sf-7 (and sf-6): failures travel through the future

    @Test
    public void thatWriteAndTruncateOnAClosedLogReturnFailedFutures() throws Exception {
        FileSagaLog log = new FileSagaLog(id("closed"));
        SagaLogEntry entry = log.write(start("e1")).join();
        log.close();
        for (CompletableFuture<?> future : new CompletableFuture<?>[]{log.write(start("e2")), log.truncate(entry.getId()), log.truncate()}) {
            CompletionException e = expectThrows(CompletionException.class, future::join);
            assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
            assertTrue(e.getCause().getMessage().contains("closed"), e.getCause().getMessage());
        }
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> log.readIncompleteSagas().toList());
        assertTrue(e.getMessage().contains(id("closed").getPath().toString()), e.getMessage());
    }

    // sf-9: ids stay unique across restarts, also when the log was emptied

    @Test
    public void thatIdsContinueAfterAnEmptiedLogIsOpenedAgain() throws Exception {
        FileSagaLogId id = id("ids");
        long last;
        try (FileSagaLog log = new FileSagaLog(id)) {
            log.write(start("e1")).join();
            last = ((FileSagaLogEntryId) log.write(start("e2")).join().getId()).id();
            log.truncate().join();
        }
        try (FileSagaLog log = new FileSagaLog(id)) {
            SagaLogEntry entry = log.write(start("e3")).join();
            long next = ((FileSagaLogEntryId) entry.getId()).id();
            assertTrue(next > last, "id after truncate() and restart " + next + " must follow " + last);
            log.truncate(entry.getId()).join(); // empties the log by id
            last = next;
        }
        try (FileSagaLog log = new FileSagaLog(id)) {
            long next = ((FileSagaLogEntryId) log.write(start("e4")).join().getId()).id();
            assertTrue(next > last, "id after truncate(id) of the last entry and restart " + next + " must follow " + last);
        }
    }

    // sf-10: a failure names the operation and the file

    @Test
    public void thatAnOpenInAReadOnlyFolderNamesTheFile() throws Exception {
        Path readOnly = folder.resolve("read-only");
        Files.createDirectories(readOnly);
        try {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            throw new SkipException("no POSIX permissions here");
        }
        try {
            if (Files.isWritable(readOnly)) {
                throw new SkipException("the folder is still writable (running as root?)");
            }
            FileSagaLogId id = new FileSagaLogId(readOnly, "01", "ro");
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> new FileSagaLog(id));
            assertTrue(e.getMessage().contains("Cannot open saga-log " + id.getPath()), e.getMessage());
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        // and the failed open released everything: the folder is writable again and the log opens
        new FileSagaLog(new FileSagaLogId(readOnly, "01", "ro")).close();
    }

    @Test
    public void thatEntriesWrittenByAnEarlierOpenAreReadBackAfterARestart() throws Exception {
        FileSagaLogId id = id("restart");
        SagaLogEntry written;
        try (FileSagaLog log = new FileSagaLog(id)) {
            written = log.write(start("e1")).join();
        }
        try (FileSagaLog log = new FileSagaLog(id)) {
            assertEquals(log.readEntries("e1").findFirst().orElseThrow(), written);
        }
    }
}
