package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogId;

import java.nio.file.Paths;

/**
 * The other process in the cross-process tests: run in a child JVM by {@link FileSagaLogCrossProcessTest}.
 * <p>
 * Arguments: {@code <folder> <log name> <mode>}. Mode {@code open} opens the log, prints {@code OPENED} or {@code BUSY}
 * and exits. Mode {@code hold} opens the log, prints {@code READY}, and keeps it open until its standard input ends.
 */
public class LogHolder {

    public static void main(String[] args) throws Exception {
        FileSagaLogPool pool = new FileSagaLogPool(Paths.get(args[0]), "child");
        SagaLogId id = pool.idFor("01", args[1]);
        switch (args[2]) {
            case "open" -> {
                try {
                    SagaLog log = pool.connect(id);
                    log.write(new SagaLogEntryBuilder().startSaga("child", "s", "{}")).join();
                    System.out.println("OPENED");
                } catch (SagaLogBusyException e) {
                    System.out.println("BUSY");
                }
                System.out.flush();
                pool.shutdown();
            }
            case "hold" -> {
                pool.connect(id).write(new SagaLogEntryBuilder().startSaga("child", "s", "{}")).join();
                System.out.println("READY");
                System.out.flush();
                while (System.in.read() != -1) {
                    // hold the log until the parent closes our input
                }
                pool.shutdown();
            }
            default -> throw new IllegalArgumentException("unknown mode " + args[2]);
        }
    }
}
