package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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

    // se-1: a step between the failing step and the join, so the join waits for a step that is never run
    private static final Saga SAGA_GAP = Saga.start("gap").linkTo("f", "a")
            .id("f").adapter("fails-late").linkTo("b")
            .id("b").adapter("ok").linkTo("j")
            .id("a").adapter("ok").linkTo("j")
            .id("j").adapter("ok").linkToEnd()
            .end();

    @Test
    public void thatNoPoolThreadStaysBlockedAfterAFailureWithAStepBeforeTheJoin() {
        for (int i = 0; i < 3; i++) {
            SagaHandoffControl control = new SagaExecution(newSagaLog(), pool, SAGA_GAP, ADAPTERS)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                    });
            CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().orTimeout(5, TimeUnit.SECONDS).join());
            assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
            assertEquals(e.getCause().getMessage(), "not an abort");
            assertNoActiveThreadsWithin(2_000);
        }
    }

    @Test
    public void thatNoPoolThreadStaysBlockedAfterACompensationFailureWithAStepBeforeTheBackwardJoin() {
        // x -> p -> z -> E and x -> q -> r -> E; z aborts, r's compensating action fails: the backward walk that
        // reaches x first must not wait for q for ever
        Saga saga = Saga.start("gap-backward").linkTo("x")
                .id("x").adapter("ok-comp").linkTo("p", "q")
                .id("p").adapter("ok-comp").linkTo("z")
                .id("z").adapter("aborts-late").linkToEnd()
                .id("q").adapter("ok-comp").linkTo("r")
                .id("r").adapter("comp-fails").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "ok-comp", (i, d) -> "ok", (i, o) -> {
                }))
                .register(new Adapter<>(String.class, "aborts-late", (i, d) -> {
                    sleep(100);
                    throw new AbortSagaException("abort");
                }, (i, o) -> {
                }))
                .register(new Adapter<>(String.class, "comp-fails", (i, d) -> "ok", (i, o) -> {
                    sleep(100);
                    throw new IllegalStateException("compensation failed");
                }));
        for (int i = 0; i < 3; i++) {
            SagaHandoffControl control = new SagaExecution(newSagaLog(), pool, saga, adapters)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                    });
            CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(e.getCause().getMessage(), "compensation failed");
            assertTrue(Arrays.stream(e.getCause().getSuppressed()).anyMatch(s -> s instanceof AbortSagaException),
                    "the abort is suppressed on the compensation's failure");
            assertNoActiveThreadsWithin(2_000);
        }
    }

    @Test
    public void thatAFailedSagaCompletesOnlyOnceItsOtherBranchesHaveEnded() {
        // se-2: S -> f, slow; f -> E; slow -> next -> E. f fails at once while slow runs: the saga reports completion
        // only after slow has ended, and next, behind the failure, never starts
        Saga saga = Saga.start("after").linkTo("f", "slow")
                .id("f").adapter("fails-at-once").linkToEnd()
                .id("slow").adapter("slow").linkTo("next")
                .id("next").adapter("ok").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "ok", (i, d) -> "ok"))
                .register(new Adapter<>(String.class, "slow", (i, d) -> {
                    sleep(300);
                    return "slow";
                }))
                .register(new Adapter<>(String.class, "fails-at-once", (i, d) -> {
                    throw new IllegalStateException("not an abort");
                }));
        SagaLog log = newSagaLog();
        String executionId = UUID.randomUUID().toString();
        List<String> atOnComplete = new CopyOnWriteArrayList<>();
        SagaHandoffControl control = new SagaExecution(log, pool, saga, adapters)
                .executeSaga(executionId, "{}", false, r -> atOnComplete.addAll(MemoryLogs.entries(log, executionId)));
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().orTimeout(5, TimeUnit.SECONDS).join());
        assertEquals(e.getCause().getMessage(), "not an abort");
        List<String> whenCompleted = MemoryLogs.entries(log, executionId);
        assertEquals(atOnComplete, whenCompleted, "onComplete saw every entry of the saga");
        assertTrue(whenCompleted.contains("End:slow"), "the running action ended before completion: " + whenCompleted);
        sleep(500);
        assertEquals(MemoryLogs.entries(log, executionId), whenCompleted, "nothing was written after completion");
        assertFalse(whenCompleted.contains("Start:next"), "a step behind the failure was started: " + whenCompleted);
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
