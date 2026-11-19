package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * The lock between processes: a second JVM ({@link LogHolder}) opens or holds the log.
 */
public class FileSagaLogCrossProcessTest {

    final Path folder = Paths.get("target/sagalog-cross-process").toAbsolutePath();

    @BeforeMethod
    public void clean() throws Exception {
        if (Files.exists(folder)) {
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(folder);
    }

    static Process startChild(Path folder, String logName, String mode) throws Exception {
        String classPath = String.join(File.pathSeparator,
                Paths.get("target/test-classes").toAbsolutePath().toString(),
                Paths.get("target/classes").toAbsolutePath().toString(),
                Paths.get(SagaLog.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(List.of(java, "-cp", classPath, LogHolder.class.getName(), folder.toString(), logName, mode))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    static String firstLine(Process process) throws Exception {
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line = out.readLine();
        return line == null ? "<no output, exit " + process.waitFor() + ">" : line;
    }

    /**
     * sf-1: a refused second open in this process must not drop the first open's OS lock.
     */
    @Test
    public void thatARefusedSecondOpenKeepsTheLockAgainstAnotherProcess() throws Exception {
        FileSagaLogPool first = new FileSagaLogPool(folder, "01");
        SagaLogId id = first.idFor("01", "L");
        first.connect(id).write(new SagaLogEntryBuilder().startSaga("e1", "s", "{}")).join();

        FileSagaLogPool second = new FileSagaLogPool(folder, "01"); // a second pool on the same folder
        expectThrows(SagaLogBusyException.class, () -> second.connect(id));

        Process child = startChild(folder, "L", "open");
        try {
            assertEquals(firstLine(child), "BUSY", "another process opened a log this process holds");
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        } finally {
            child.destroyForcibly();
            first.shutdown();
        }
    }

    /**
     * sf-3: delete of a log another process holds fails and leaves the log.
     */
    @Test
    public void thatDeleteOfALogAnotherProcessHoldsIsRefused() throws Exception {
        FileSagaLogPool pool = new FileSagaLogPool(folder, "02");
        SagaLogId id = pool.idFor("01", "H");
        Process child = startChild(folder, "H", "hold");
        try {
            assertEquals(firstLine(child), "READY");
            expectThrows(SagaLogBusyException.class, () -> pool.delete(id));
            Path path = ((FileSagaLogId) id).getPath();
            assertTrue(Files.exists(path), "the held log was deleted");
            expectThrows(SagaLogBusyException.class, () -> pool.connect(id)); // still held by the child
        } finally {
            child.getOutputStream().close();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
            child.destroyForcibly();
        }
        assertTrue(pool.delete(id), "free once the other process closed it");
    }

    /**
     * sf-3: delete of a log another pool in this process holds fails too.
     */
    @Test
    public void thatDeleteOfALogAnotherPoolInThisProcessHoldsIsRefused() throws Exception {
        FileSagaLogPool holder = new FileSagaLogPool(folder, "01");
        SagaLogId id = holder.idFor("01", "P");
        holder.connect(id).write(new SagaLogEntryBuilder().startSaga("e1", "s", "{}")).join();
        FileSagaLogPool other = new FileSagaLogPool(folder, "01");
        expectThrows(SagaLogBusyException.class, () -> other.delete(id));
        assertTrue(Files.exists(((FileSagaLogId) id).getPath()));
        holder.shutdown();
        assertTrue(other.delete(id));
    }
}
