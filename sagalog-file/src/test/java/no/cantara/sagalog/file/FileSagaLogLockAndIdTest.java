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

    @Test
    public void thatAnyTextIsStoredAsItIs() throws Exception {
        String big = "x".repeat(1_000_000);
        List<String> data = java.util.Arrays.asList("OK", "[1,2]", "\"a json string\"", "two words", "", null,
                "æøå ✓ \n tab\t{not json", "{\"a\":1}", big);
        FileSagaLog log = new FileSagaLog(logId);
        log.write(new SagaLogEntryBuilder().startSaga("exec with spaces", "a saga name", "input that is not json")).join();
        for (String d : data) {
            log.write(new SagaLogEntryBuilder().endAction("exec with spaces", "node", d)).join();
        }
        log.close();

        FileSagaLog reopened = new FileSagaLog(logId);
        List<SagaLogEntry> entries = reopened.readIncompleteSagas().collect(Collectors.toList());
        assertEquals(entries.get(0).getSagaName(), "a saga name");
        assertEquals(entries.get(0).getJsonData(), "input that is not json");
        assertEquals(entries.get(0).getExecutionId(), "exec with spaces");
        assertEquals(entries.subList(1, entries.size()).stream().map(SagaLogEntry::getJsonData).collect(Collectors.toList()), data);
        // a saga name without data keeps its last character
        reopened.write(new SagaLogEntryBuilder().startSaga("e2", "name", null)).join();
        assertEquals(reopened.readEntries("e2").findFirst().orElseThrow().getSagaName(), "name");
        reopened.close();
    }

    @Test
    public void thatALogInTheOldFormatIsRefused() throws Exception {
        no.cantara.sagalog.file.queue.QueueFile old = new no.cantara.sagalog.file.queue.QueueFile.Builder(logId.getPath().toFile()).build();
        old.add("0 exec Start S saga {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); // the format before 2.0.1
        old.close();
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> new FileSagaLog(logId));
        assertTrue(e.getMessage().contains("unsupported format"), e.getMessage());
        // the failed open released its lock: another open fails on the format again, not with SagaLogBusyException
        expectThrows(IllegalStateException.class, () -> new FileSagaLog(logId));
    }
}
