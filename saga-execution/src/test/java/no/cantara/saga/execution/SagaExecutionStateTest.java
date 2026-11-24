package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * {@link SagaHandoffControl#getState()} shows where an execution is: the nodes running, the nodes waiting for others,
 * the phase, and the failure.
 */
public class SagaExecutionStateTest {

    private SelectableThreadPoolExecutor pool;
    private CountDownLatch release;

    @BeforeMethod
    public void createPool() {
        pool = new SelectableThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        release = new CountDownLatch(1);
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        release.countDown();
        pool.shutdownNow();
    }

    private String awaitRelease() {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "released";
    }

    /**
     * Waits for a state the saga reaches on its own; the bound is generous, and the poll is only how the test observes
     * a snapshot API.
     */
    private static SagaExecutionState awaitState(SagaHandoffControl control, Predicate<SagaExecutionState> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        SagaExecutionState state = control.getState();
        while (!condition.test(state)) {
            if (System.nanoTime() > deadline) {
                fail("the saga did not reach the state; last: " + state);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
            state = control.getState();
        }
        return state;
    }

    @Test
    public void thatTheStateShowsTheRunningNodeAndTheJoinWaitingForIt() {
        Saga saga = Saga.start("fork-join").linkTo("a", "b")
                .id("a").adapter("slow").linkTo("j")
                .id("b").adapter("fast").linkTo("j")
                .id("j").adapter("fast").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "slow", (i, d) -> awaitRelease()))
                .register(new Adapter<>(String.class, "fast", (i, d) -> "fast"));
        String executionId = UUID.randomUUID().toString();
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(executionId, "{}", false, r -> {
                });

        // b's walk ends just after it signals j, and a's walk may still be on its way to a's visit; a then waits for the
        // release, so the state holds still
        SagaExecutionState state = awaitState(control, s -> s.getWaitingNodeIds().equals(Map.of("j", 1)) && s.getWalks() == 1
                && s.getRunningNodeIds().equals(Set.of("a")));
        assertEquals(state.getExecutionId(), executionId);
        assertEquals(state.getPhase(), SagaExecutionState.Phase.FORWARD);
        assertEquals(state.getRunningNodeIds(), Set.of("a"), "a runs: " + state);
        assertTrue(state.getVisitedNodeIds().containsAll(Set.of("S", "b")), "S and b were visited: " + state);
        assertEquals(state.getWalks(), 1, "only a's walk is left; b's ended at the join: " + state);
        assertNull(state.getFailure());

        release.countDown();
        control.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join();
        // the completion future completes within E's visit, so E's walk ends a moment later
        state = awaitState(control, s -> s.getWalks() == 0);
        assertEquals(state.getPhase(), SagaExecutionState.Phase.COMPLETED);
        assertEquals(state.getRunningNodeIds(), Set.of());
        assertEquals(state.getWaitingNodeIds(), Map.of());
        assertEquals(state.getVisitedNodeIds(), Set.of("S", "a", "b", "j", "E"));
        assertEquals(state.getWalks(), 0);
    }

    @Test
    public void thatTheStateShowsTheRollbackAndTheCompensationRunning() {
        Saga saga = Saga.start("aborts").linkTo("a")
                .id("a").adapter("compensatesSlowly").linkTo("b")
                .id("b").adapter("aborts").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "compensatesSlowly", (i, d) -> "a", (i, o) -> awaitRelease()))
                .register(new Adapter<>(String.class, "aborts", (i, d) -> {
                    throw new AbortSagaException("abort at b");
                }));
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });

        // a also runs forward, before b aborts: wait for its compensation
        SagaExecutionState state = awaitState(control, s -> s.getPhase() == SagaExecutionState.Phase.ROLLBACK
                && s.getRunningNodeIds().contains("a"));
        assertTrue(state.getVisitedNodeIds().containsAll(Set.of("E", "b")), "E and b were visited backward: " + state);
        assertNull(state.getFailure(), "an abort is not a failure while it rolls back: " + state);

        release.countDown();
        control.getCompletionFuture().copy().handle((r, t) -> t).orTimeout(10, TimeUnit.SECONDS).join();
        // the completion future completes within S's backward visit, so S's walk ends a moment later
        state = awaitState(control, s -> s.getWalks() == 0);
        assertEquals(state.getPhase(), SagaExecutionState.Phase.COMPLETED);
        assertEquals(state.getVisitedNodeIds(), Set.of("E", "b", "a", "S"), "the rollback visited every node: " + state);
    }

    @Test
    public void thatTheStateGivesTheFailure() {
        IllegalStateException failure = new IllegalStateException("a failed");
        Saga saga = Saga.start("fails").linkTo("a")
                .id("a").adapter("fails").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader().register(new Adapter<>(String.class, "fails", (i, d) -> {
            throw failure;
        }));
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });

        control.getCompletionFuture().copy().handle((r, t) -> t).orTimeout(10, TimeUnit.SECONDS).join();
        SagaExecutionState state = awaitState(control, s -> s.getWalks() == 0);
        assertEquals(state.getPhase(), SagaExecutionState.Phase.COMPLETED);
        assertSame(state.getFailure(), failure);
        assertEquals(state.getVisitedNodeIds(), Set.of("S"), "a failed, so it was not visited: " + state);
        assertEquals(state.getRunningNodeIds(), Set.of());
    }
}
