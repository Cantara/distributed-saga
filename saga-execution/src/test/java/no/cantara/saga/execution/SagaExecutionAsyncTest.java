package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.ActionOutputSerializer;
import no.cantara.saga.execution.adapter.ActionOutputSerializerToStringAndStringConstructor;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.execution.adapter.AsyncSagaAdapter;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Asynchronous steps ({@link AsyncSagaAdapter}): the stage completes on a thread of another executor, as an RxJava
 * scheduler or a database driver would; the saga continues on its own pool, holds no pool thread while a step runs,
 * keeps the log order, and handles aborts and failures as for a synchronous step.
 */
public class SagaExecutionAsyncTest {

    private SelectableThreadPoolExecutor pool;
    private ExecutorService foreign;
    private CountDownLatch release;

    @BeforeMethod
    public void createPools() {
        pool = new SelectableThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(r, "saga-pool"));
        foreign = Executors.newCachedThreadPool(r -> new Thread(r, "foreign"));
        release = new CountDownLatch(1);
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPools() {
        release.countDown();
        pool.shutdownNow();
        foreign.shutdownNow();
    }

    /**
     * An asynchronous adapter whose action is given as a function from the saga input to a stage.
     */
    static final class Async implements AsyncSagaAdapter<String> {
        final String name;
        final Function<Object, CompletionStage<String>> action;
        final Function<String, CompletionStage<Void>> compensation;

        Async(String name, Function<Object, CompletionStage<String>> action, Function<String, CompletionStage<Void>> compensation) {
            this.name = name;
            this.action = action;
            this.compensation = compensation;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CompletionStage<String> executeActionAsync(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
            return action.apply(sagaInput);
        }

        @Override
        public CompletionStage<Void> executeCompensatingActionAsync(SagaNode sagaNode, Object sagaInput, String actionOutput) {
            return compensation.apply(actionOutput);
        }

        @Override
        public boolean hasCompensatingAction() {
            return compensation != null;
        }

        @Override
        public ActionOutputSerializer<String> serializer() {
            return new ActionOutputSerializerToStringAndStringConstructor<>(String.class);
        }
    }

    private CompletableFuture<String> onForeign(String output) {
        return CompletableFuture.supplyAsync(() -> {
            await(release);
            return output;
        }, foreign);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<SagaLogEntryType> entryTypes(SagaLog log, String executionId, String nodeId) {
        return log.readEntries(executionId).filter(e -> nodeId.equals(e.getNodeId()))
                .map(SagaLogEntry::getEntryType).collect(Collectors.toList());
    }

    private static Throwable outcome(SagaHandoffControl control) {
        try {
            control.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join();
            return null;
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                fail("the saga did not complete; state: " + control.getState());
            }
            return e.getCause();
        }
    }

    @Test
    public void thatTheSagaContinuesOnItsPoolAndWritesEndBeforeTheNextStep() {
        Saga saga = Saga.start("async").linkTo("a")
                .id("a").adapter("async").linkTo("b")
                .id("b").adapter("sync").linkToEnd()
                .end();
        List<String> threads = new CopyOnWriteArrayList<>();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Async("async", input -> onForeign("from-a"), null))
                .register(new Adapter<>(String.class, "sync", (i, d) -> {
                    threads.add("b:" + Thread.currentThread().getName());
                    return "b got " + d.values().stream().filter(v -> v != null).findFirst().orElse(null);
                }));
        SagaLog log = MemoryLogs.newLog();
        String executionId = UUID.randomUUID().toString();
        SagaHandoffControl control = new SagaExecution(log, pool, saga, adapters).executeSaga(executionId, "{}", false,
                r -> threads.add("onComplete:" + Thread.currentThread().getName()), c -> {
                }, c -> threads.add("post " + c.getNode().id + ":" + Thread.currentThread().getName()));

        release.countDown();
        assertEquals(outcome(control), null);

        assertEquals(entryTypes(log, executionId, "a"), List.of(SagaLogEntryType.Start, SagaLogEntryType.End));
        assertEquals(entryTypes(log, executionId, "b"), List.of(SagaLogEntryType.Start, SagaLogEntryType.End));
        assertTrue(threads.stream().noneMatch(t -> t.endsWith(":foreign")), "nothing ran on the foreign thread: " + threads);
        assertTrue(threads.contains("post a:saga-pool"), "a's postAction ran on the pool: " + threads);
        assertTrue(threads.contains("b:saga-pool"), "b ran on the pool: " + threads);
    }

    @Test
    public void thatAnAsyncStepHoldsNoPoolThreadWhileItRuns() {
        Saga saga = Saga.start("waits").linkTo("a")
                .id("a").adapter("async").linkToEnd()
                .end();
        CountDownLatch bothStarted = new CountDownLatch(2);
        AdapterLoader adapters = new AdapterLoader().register(new Async("async", input -> {
            bothStarted.countDown();
            return onForeign("a");
        }, null));
        // one pool thread, two sagas: the second step starts only if the first holds no thread while it runs
        SagaHandoffControl first = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        SagaHandoffControl second = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        await(bothStarted);
        assertEquals(first.getState().getRunningNodeIds(), Set.of("a"), "a is running, without a thread: " + first.getState());

        release.countDown();
        assertEquals(outcome(first), null);
        assertEquals(outcome(second), null);
    }

    @Test
    public void thatAStageThatFailsWithAnAbortRollsBackAndCompensatesAsynchronously() {
        Saga saga = Saga.start("aborts").linkTo("a")
                .id("a").adapter("compensated").linkTo("b")
                .id("b").adapter("aborts").linkToEnd()
                .end();
        List<String> compensations = new CopyOnWriteArrayList<>();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Async("compensated", input -> onForeign("a-output"),
                        output -> CompletableFuture.runAsync(() -> compensations.add(output + " on " + Thread.currentThread().getName()), foreign)))
                .register(new Async("aborts", input -> CompletableFuture.supplyAsync(() -> {
                    throw new AbortSagaException("abort at b"); // the stage fails with it, inside a CompletionException
                }, foreign), null));
        SagaLog log = MemoryLogs.newLog();
        String executionId = UUID.randomUUID().toString();
        SagaHandoffControl control = new SagaExecution(log, pool, saga, adapters)
                .executeSaga(executionId, "{}", false, r -> {
                });

        release.countDown();
        Throwable failure = outcome(control);
        assertTrue(failure instanceof AbortSagaException, "the saga rolled back: " + failure);
        assertEquals(compensations, List.of("a-output on foreign"), "a was compensated with its logged output");
        assertEquals(entryTypes(log, executionId, "a"), List.of(SagaLogEntryType.Start, SagaLogEntryType.End,
                SagaLogEntryType.CompStart, SagaLogEntryType.Comp));
        assertEquals(entryTypes(log, executionId, "b"), List.of(SagaLogEntryType.Start, SagaLogEntryType.Abort));
    }

    @Test
    public void thatAnAbortThrownByExecuteActionAsyncRollsBack() {
        Saga saga = Saga.start("aborts").linkTo("a")
                .id("a").adapter("aborts").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader().register(new Async("aborts", input -> {
            throw new AbortSagaException("abort before any stage");
        }, null));
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        assertTrue(outcome(control) instanceof AbortSagaException);
    }

    @Test
    public void thatAStageThatFailsFailsTheSagaWithItsException() {
        IllegalStateException unchecked = new IllegalStateException("a failed");
        IOException checked = new IOException("b failed");
        Saga saga = Saga.start("fails").linkTo("a")
                .id("a").adapter("fails").linkToEnd()
                .end();

        AdapterLoader uncheckedAdapters = new AdapterLoader().register(new Async("fails", input -> CompletableFuture.failedFuture(unchecked), null));
        SagaHandoffControl uncheckedControl = new SagaExecution(MemoryLogs.newLog(), pool, saga, uncheckedAdapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        assertSame(outcome(uncheckedControl), unchecked, "an unchecked failure is the saga's failure");

        AdapterLoader checkedAdapters = new AdapterLoader().register(new Async("fails", input -> CompletableFuture.failedFuture(checked), null));
        List<Throwable> onComplete = new CopyOnWriteArrayList<>();
        SagaHandoffControl checkedControl = new SagaExecution(MemoryLogs.newLog(), pool, saga, checkedAdapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.add(r.getFailureCause()));
        assertSame(outcome(checkedControl), checked, "join() gives the checked failure as the cause");
        assertEquals(onComplete.size(), 1);
        assertTrue(onComplete.get(0) instanceof CompletionException, "a checked failure is wrapped: " + onComplete.get(0));
        assertSame(onComplete.get(0).getCause(), checked);
    }

    @Test
    public void thatTheRollbackWaitsForAnAsyncStepStillRunningOnAnotherBranch() {
        Saga saga = Saga.start("fork").linkTo("a", "b")
                .id("a").adapter("aborts").linkToEnd()
                .id("b").adapter("slow").linkToEnd()
                .end();
        CountDownLatch bStarted = new CountDownLatch(1);
        List<String> compensations = new CopyOnWriteArrayList<>();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "aborts", (i, d) -> {
                    await(bStarted);
                    throw new AbortSagaException("abort at a");
                }))
                .register(new Async("slow", input -> {
                    bStarted.countDown();
                    return onForeign("b-output");
                }, output -> {
                    compensations.add(output);
                    return CompletableFuture.completedFuture(null);
                }));
        SagaLog log = MemoryLogs.newLog();
        String executionId = UUID.randomUUID().toString();
        SagaHandoffControl control = new SagaExecution(log, pool, saga, adapters)
                .executeSaga(executionId, "{}", false, r -> {
                });

        awaitState(control, s -> s.getVisitedNodeIds().contains("a") && s.getRunningNodeIds().equals(Set.of("b")));
        assertEquals(control.getState().getPhase(), SagaExecutionState.Phase.FORWARD, "no rollback while b runs");
        assertFalse(control.getCompletionFuture().isDone());

        release.countDown();
        assertTrue(outcome(control) instanceof AbortSagaException);
        assertEquals(compensations, List.of("b-output"), "b ended before the rollback, which compensated it with its output");
        assertEquals(entryTypes(log, executionId, "b"), List.of(SagaLogEntryType.Start, SagaLogEntryType.End,
                SagaLogEntryType.CompStart, SagaLogEntryType.Comp));
    }

    @Test
    public void thatAContinuationThePoolRejectsFailsTheSaga() {
        Saga saga = Saga.start("rejected").linkTo("a")
                .id("a").adapter("async").linkToEnd()
                .end();
        CountDownLatch started = new CountDownLatch(1);
        AdapterLoader adapters = new AdapterLoader().register(new Async("async", input -> {
            started.countDown();
            return onForeign("a");
        }, null));
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        await(started);
        pool.shutdown(); // the step's stage completes after: its continuation is rejected
        release.countDown();
        Throwable failure = outcome(control);
        assertTrue(failure instanceof RejectedExecutionException, "the saga fails with the rejection: " + failure);
    }

    @Test
    public void thatAStageOfNullFailsTheSaga() {
        Saga saga = Saga.start("null").linkTo("a")
                .id("a").adapter("null").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader().register(new Async("null", input -> null, null));
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        Throwable failure = outcome(control);
        assertTrue(failure instanceof NullPointerException, "" + failure);
        assertNotNull(failure.getMessage());
    }

    private static void awaitState(SagaHandoffControl control, Predicate<SagaExecutionState> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.test(control.getState())) {
            if (System.nanoTime() > deadline) {
                fail("the saga did not reach the state; last: " + control.getState());
            }
            try {
                Thread.sleep(2); // a snapshot API has no event to wait on; the bound above is generous
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }
}
