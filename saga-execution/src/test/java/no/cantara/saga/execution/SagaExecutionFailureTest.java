package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * An action that throws something other than AbortSagaException, in a node that has a sibling: the walk waiting at the
 * join for the failed node must end, so no pool thread stays blocked.
 */
public class SagaExecutionFailureTest {

    // S -> txlog -> [persistence, search] -> E, the shape of Linked Data Store's write saga
    private static final Saga SAGA = Saga.start("failing").linkTo("txlog")
            .id("txlog").adapter("ok").linkTo("persistence", "search")
            .id("persistence").adapter("fails-late").linkToEnd()
            .id("search").adapter("ok").linkToEnd()
            .end();

    // the same shape, failing at once, so the two walks that see the failure finish at nearly the same time
    private static final Saga SAGA_FAST_FAILURE = Saga.start("failing-fast").linkTo("txlog")
            .id("txlog").adapter("ok").linkTo("persistence", "search")
            .id("persistence").adapter("fails-at-once").linkToEnd()
            .id("search").adapter("ok").linkToEnd()
            .end();

    private static final AdapterLoader ADAPTERS = new AdapterLoader()
            .register(new Adapter<>(String.class, "ok", (input, dependeesOutput) -> "ok"))
            .register(new Adapter<>(String.class, "fails-at-once", (input, dependeesOutput) -> {
                throw new IllegalStateException("not an abort");
            }))
            .register(new Adapter<>(String.class, "fails-late", (input, dependeesOutput) -> {
                sleep(200); // let the sibling reach E and wait there for this node
                throw new IllegalStateException("not an abort");
            }));

    private SelectableThreadPoolExectutor pool;

    @BeforeMethod
    public void createPool() {
        pool = new SelectableThreadPoolExectutor(10, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        pool.shutdownNow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private SagaLog newSagaLog() {
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> "no.cantara.sagalog.memory.MemorySagaLogInitializer".equals(c.type().getName()))
                .findFirst().orElseThrow().get();
        SagaLogPool logPool = initializer.initialize(initializer.configurationOptionsAndDefaults());
        return logPool.connect(logPool.idFor(logPool.getLocalClusterInstanceId(), "failure-" + UUID.randomUUID()));
    }

    private SagaHandoffControl runFailingSaga() {
        SagaHandoffControl control = new SagaExecution(newSagaLog(), pool, SAGA, ADAPTERS)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
        return control;
    }

    private void assertNoActiveThreadsWithin(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            sleep(20);
        }
        assertEquals(pool.getActiveCount(), 0, "pool threads still busy after the saga failed");
    }

    @Test
    public void thatNoPoolThreadStaysBlockedAfterAFailureNextToASibling() {
        for (int i = 0; i < 3; i++) {
            runFailingSaga();
            assertNoActiveThreadsWithin(2_000);
        }
    }

    @Test
    public void thatTheSagaFailsWithTheActionsOwnExceptionEveryTime() {
        // two walks see the failure (the failing node's own walk, and the sibling's walk waiting at E) and race to
        // complete the saga; both must report the action's exception, not a wrapper around it
        for (int i = 0; i < 200; i++) {
            SagaHandoffControl control = new SagaExecution(newSagaLog(), pool, SAGA_FAST_FAILURE, ADAPTERS)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                    });
            CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
            assertTrue(e.getCause() instanceof IllegalStateException, "run " + i + ": " + e.getCause());
            assertEquals(e.getCause().getMessage(), "not an abort", "run " + i);
        }
    }

    @Test
    public void thatWaitingForTheWalksOfAFailedSagaReturnsInsteadOfHanging() throws Exception {
        SagaHandoffControl control = runFailingSaga();
        CompletableFuture<Throwable> waited = CompletableFuture.supplyAsync(() -> {
            try {
                control.getTraversalResult().waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(5, TimeUnit.SECONDS);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        Throwable thrown = waited.get(3, TimeUnit.SECONDS); // a TimeoutException here means it hangs
        assertTrue(thrown != null, "waiting for a failed saga's walks should report the failure");
    }
}
