package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * se-4: a walk that the executor drops ({@code shutdownNow}) or rejects completes the saga's futures exceptionally.
 */
public class SagaExecutionShutdownTest {

    private static final AdapterLoader ADAPTERS = new AdapterLoader()
            .register(new Adapter<>(String.class, "ok", (i, d) -> "ok"))
            .register(new Adapter<>(String.class, "aborts", (i, d) -> {
                throw new AbortSagaException("abort");
            }));

    private static Throwable failureOf(Future<?> future) {
        CompletionException e = expectThrows(CompletionException.class,
                () -> ((java.util.concurrent.CompletableFuture<?>) future).orTimeout(3, TimeUnit.SECONDS).join());
        return e.getCause();
    }

    private static void cancelAll(List<Runnable> dropped) {
        for (Runnable task : dropped) {
            if (task instanceof Future) {
                ((Future<?>) task).cancel(false);
            }
        }
    }

    @Test
    public void thatASagaWhoseFirstWalkIsDroppedByShutdownNowFailsOnceTheDroppedTasksAreCancelled() {
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch busy = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                busy.await();
                return null;
            });
            Saga saga = Saga.start("queued").linkTo("a").id("a").adapter("ok").linkToEnd().end();
            AtomicInteger onComplete = new AtomicInteger();
            SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, ADAPTERS)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.incrementAndGet());
            List<Runnable> dropped = pool.shutdownNow();
            assertEquals(dropped.size(), 1, "the saga's first walk was queued");
            cancelAll(dropped);
            assertTrue(failureOf(control.getHandoffFuture()) instanceof CancellationException);
            assertTrue(failureOf(control.getCompletionFuture()) instanceof CancellationException);
            assertEquals(onComplete.get(), 1);
        } finally {
            busy.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void thatASagaWithAWalkDroppedByShutdownNowFailsWhenItsRunningWalkIsInterrupted() {
        // one thread: the walk of S queues the walk of a and runs b itself, where it blocks until shutdownNow
        // interrupts it; the queued walk of a is dropped and never runs. The saga completes without the caller
        // cancelling the dropped task.
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch inB = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "ok", (i, d) -> "ok"))
                .register(new Adapter<>(String.class, "blocks", (i, d) -> {
                    inB.countDown();
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("b was interrupted", e);
                    }
                    return "b";
                }));
        Saga saga = Saga.start("dropped").linkTo("a", "b")
                .id("a").adapter("ok").linkToEnd()
                .id("b").adapter("blocks").linkToEnd()
                .end();
        try {
            AtomicInteger onComplete = new AtomicInteger();
            SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.incrementAndGet());
            assertTrue(inB.await(3, TimeUnit.SECONDS));
            List<Runnable> dropped = pool.shutdownNow();
            assertEquals(dropped.size(), 1, "the walk of the other branch was queued");
            assertEquals(failureOf(control.getCompletionFuture()).getMessage(), "b was interrupted");
            assertEquals(onComplete.get(), 1);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void thatAnAbortWhileOtherWorkHoldsThePoolStillRollsBack() {
        // the aborting action fills the pool's one thread with other work; the rollback must not wait for a thread
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch busy = new CountDownLatch(1);
        AdapterLoader adapters = new AdapterLoader().register(new Adapter<>(String.class, "aborts", (i, d) -> {
            pool.submit(() -> {
                busy.await();
                return null;
            });
            throw new AbortSagaException("abort");
        }));
        Saga saga = Saga.start("rollback-queued").linkTo("a").id("a").adapter("aborts").linkToEnd().end();
        try {
            AtomicInteger onComplete = new AtomicInteger();
            SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, adapters)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.incrementAndGet());
            MemoryLogs.sleep(300);
            cancelAll(pool.shutdownNow());
            Throwable failure = failureOf(control.getCompletionFuture());
            assertTrue(failure instanceof AbortSagaException || failure instanceof CancellationException, String.valueOf(failure));
            assertEquals(onComplete.get(), 1);
        } finally {
            busy.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void thatARejectedFirstWalkFailsTheSagasFutures() {
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch busy = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                busy.await();
                return null;
            });
            Saga saga = Saga.start("rejected").linkTo("a").id("a").adapter("ok").linkToEnd().end();
            AtomicInteger onComplete = new AtomicInteger();
            SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, ADAPTERS)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.incrementAndGet());
            assertTrue(failureOf(control.getHandoffFuture()) instanceof RejectedExecutionException);
            assertTrue(failureOf(control.getCompletionFuture()) instanceof RejectedExecutionException);
            assertEquals(onComplete.get(), 1);
        } finally {
            busy.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void thatASagaStartedAfterShutdownFails() {
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.shutdown();
        Saga saga = Saga.start("after-shutdown").linkTo("a").id("a").adapter("ok").linkToEnd().end();
        SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, saga, ADAPTERS)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        assertTrue(failureOf(control.getCompletionFuture()) instanceof RejectedExecutionException);
    }
}
