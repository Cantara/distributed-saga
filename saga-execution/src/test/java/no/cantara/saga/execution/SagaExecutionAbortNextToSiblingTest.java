package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * An action that throws AbortSagaException in a node that has a sibling, in the shape
 * S -> txlog -> [persistence, search] -> E: which entries the log holds when the completion future fails, and which
 * compensating actions run (each preceded by CompStart). The sibling either has finished before the abort or is still running during it.
 */
public class SagaExecutionAbortNextToSiblingTest {

    private static final Saga SAGA = Saga.start("abort-next-to-sibling").linkTo("txlog")
            .id("txlog").adapter("txlog").linkTo("persistence", "search")
            .id("persistence").adapter("persistence").linkToEnd()
            .id("search").adapter("search").linkToEnd()
            .end();

    private SelectableThreadPoolExecutor pool;
    private final List<String> compensated = new CopyOnWriteArrayList<>();

    @BeforeMethod
    public void setUp() {
        pool = new SelectableThreadPoolExecutor(10, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        compensated.clear();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        pool.shutdownNow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private AdapterLoader adapters(long persistenceDelayMs, long searchDelayMs) {
        return new AdapterLoader()
                .register(new Adapter<>(String.class, "txlog",
                        (input, dependees) -> "txlog-out",
                        (input, output) -> compensated.add("txlog:" + output)))
                .register(new Adapter<>(String.class, "persistence",
                        (input, dependees) -> {
                            sleep(persistenceDelayMs);
                            throw new AbortSagaException("Unable to write data using persistence.");
                        },
                        (input, output) -> compensated.add("persistence:" + output)))
                .register(new Adapter<>(String.class, "search",
                        (input, dependees) -> {
                            sleep(searchDelayMs);
                            return "search-out";
                        },
                        (input, output) -> compensated.add("search:" + output)));
    }

    private SagaLog newSagaLog() {
        SagaLogInitializer initializer = MemoryLogs.memoryInitializer();
        SagaLogPool logPool = initializer.initialize(initializer.configurationOptionsAndDefaults());
        return logPool.connect(logPool.idFor(logPool.getLocalClusterInstanceId(), "abort-" + UUID.randomUUID()));
    }

    private String run(long persistenceDelayMs, long searchDelayMs) {
        SagaLog sagaLog = newSagaLog();
        SagaHandoffControl control = new SagaExecution(sagaLog, pool, SAGA, adapters(persistenceDelayMs, searchDelayMs))
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
        assertTrue(e.getCause() instanceof AbortSagaException, String.valueOf(e.getCause()));
        // the entries as they are when the completion future has failed; nothing truncates the log
        return sagaLog.readIncompleteSagas()
                .map(entry -> entry.getEntryType() + ":" + entry.getNodeId())
                .collect(Collectors.joining(" "));
    }

    private static String sorted(String entries) {
        return List.of(entries.split(" ")).stream().sorted().collect(Collectors.joining(" "));
    }

    @Test
    public void thatASiblingThatHasFinishedIsCompensated() {
        String entries = run(200, 0);
        // order between the parallel branches varies; compare as sets, then check the order that matters
        assertEquals(sorted(entries), sorted(
                "Start:S Start:txlog End:txlog Start:persistence Start:search End:search Abort:persistence"
                        + " CompStart:search Comp:search CompStart:txlog Comp:txlog End:E"));
        assertTrue(entries.endsWith("CompStart:txlog Comp:txlog End:E"), entries);
        assertEquals(compensated, List.of("search:search-out", "txlog:txlog-out"));
    }

    @Test
    public void thatASiblingStillRunningDuringTheAbortFinishesAndIsCompensated() {
        String entries = run(0, 300);
        assertEquals(sorted(entries), sorted(
                "Start:S Start:txlog End:txlog Start:persistence Start:search Abort:persistence End:search"
                        + " CompStart:search Comp:search CompStart:txlog Comp:txlog End:E"));
        assertTrue(entries.indexOf("End:search") < entries.indexOf("Comp:search"), entries);
        assertTrue(entries.endsWith("CompStart:txlog Comp:txlog End:E"), entries);
        assertEquals(compensated, List.of("search:search-out", "txlog:txlog-out"));
    }
}
