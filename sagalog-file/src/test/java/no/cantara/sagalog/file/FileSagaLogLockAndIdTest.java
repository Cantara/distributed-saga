package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Entry ids continue after a restart, and one log file is open in one place at a time.
 */
public class FileSagaLogLockAndIdTest {

    FileSagaLogId logId;

    @BeforeMethod
    public void clean() throws Exception {
        logId = new FileSagaLogId(Paths.get("target"), "01", "lock-and-id");
        Files.deleteIfExists(logId.getPath());
        Files.deleteIfExists(FileSagaLog.lockPath(logId.getPath()));
    }

    static SagaLogEntryBuilder start(String executionId) {
        return new SagaLogEntryBuilder().startSaga(executionId, "saga", "{}");
    }

    @Test
    public void thatIdsContinueAfterTheLogIsOpenedAgain() throws Exception {
        FileSagaLog first = new FileSagaLog(logId);
        SagaLogEntry a = first.write(start("e1")).join();
        SagaLogEntry b = first.write(start("e2")).join();
        first.close();

        FileSagaLog second = new FileSagaLog(logId);
        SagaLogEntry c = second.write(start("e3")).join();
        assertTrue(((FileSagaLogEntryId) c.getId()).id > ((FileSagaLogEntryId) b.getId()).id,
                "id after restart " + c.getId() + " must follow " + b.getId());

        // truncate(id) of the entry written before the restart removes only the entries up to it
        second.truncate(a.getId()).join();
        List<String> left = second.readIncompleteSagas().map(SagaLogEntry::getExecutionId).collect(Collectors.toList());
        assertEquals(left, List.of("e2", "e3"));
        second.close();
    }

    @Test
    public void thatALogCannotBeOpenedTwice() throws Exception {
        FileSagaLog first = new FileSagaLog(logId);
        expectThrows(SagaLogBusyException.class, () -> new FileSagaLog(logId));
        first.close();

        FileSagaLog again = new FileSagaLog(logId); // free once closed
        again.write(start("e1")).join();
        again.close();
    }

    @Test
    public void thatDeleteRemovesTheLockFile() throws Exception {
        FileSagaLogPool pool = new FileSagaLogPool(Paths.get("target"), "01");
        pool.connect(logId).write(start("e1")).join();
        pool.remove(logId);
        Path lock = FileSagaLog.lockPath(logId.getPath());
        assertTrue(Files.exists(lock));
        pool.delete(logId);
        assertTrue(!Files.exists(lock) && !Files.exists(logId.getPath()));
    }

    @Test
    public void thatEveryEntryTypeIsReadBack() throws Exception {
        FileSagaLog log = new FileSagaLog(logId);
        for (no.cantara.sagalog.SagaLogEntryType type : no.cantara.sagalog.SagaLogEntryType.values()) {
            log.write(new SagaLogEntryBuilder().executionId("e1").entryType(type).nodeId("n")).join();
        }
        List<no.cantara.sagalog.SagaLogEntryType> read = log.readIncompleteSagas().map(SagaLogEntry::getEntryType).collect(Collectors.toList());
        assertEquals(read, List.of(no.cantara.sagalog.SagaLogEntryType.values()));
        log.close();
    }
}
