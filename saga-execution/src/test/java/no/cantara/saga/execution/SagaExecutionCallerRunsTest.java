package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * se-5: a pool whose rejection policy runs the task on the submitting thread ({@code CallerRunsPolicy}) must not
 * deadlock a saga; a walk that would run on the submitting thread is rejected instead.
 */
public class SagaExecutionCallerRunsTest {

    private static final AdapterLoader ADAPTERS = new AdapterLoader()
            .register(new Adapter<>(String.class, "ok", (i, d) -> "ok"))
            .register(new Adapter<>(String.class, "aborts", (i, d) -> {
                throw new AbortSagaException("abort");
            }));

    private SelectableThreadPoolExecutor pool;
    private CountDownLatch release;

    @BeforeMethod
    public void createPool() {
        // one thread, no queue: a submit while the thread is busy runs the task on the submitting thread
        pool = new SelectableThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        release = new CountDownLatch(1);
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        release.countDown();
        pool.shutdownNow();
    }

    /**
     * Runs executeSaga on its own thread, so that a deadlock of the calling thread shows as a timeout.
     */
    private SagaHandoffControl executeWithin(Saga saga, long ms) throws Exception {
        CompletableFuture<SagaHandoffControl> started = new CompletableFuture<>();
        Thread caller = new Thread(() -> {
            try {
                started.complete(new SagaExecution(MemoryLogs.newLog(), pool, saga, ADAPTERS)
                        .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                        }));
            } catch (Throwable t) {
                started.completeExceptionally(t);
            }
        }, "saga-caller");
        caller.setDaemon(true);
        caller.start();
        try {
            return started.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("executeSaga deadlocked the calling thread", e);
        }
    }

    @Test
    public void thatAnAbortOnAWalkTheCallerWouldRunDoesNotDeadlockTheCaller() throws Exception {
        pool.submit(() -> {
            release.await();
            return null;
        }); // the one thread is busy
        Saga saga = Saga.start("caller-runs-abort").linkTo("a").id("a").adapter("aborts").linkToEnd().end();
        SagaHandoffControl control = executeWithin(saga, 3_000);
        CompletionException e = expectThrows(CompletionException.class,
                () -> control.getCompletionFuture().orTimeout(3, TimeUnit.SECONDS).join());
        assertTrue(e.getCause() instanceof RejectedExecutionException, String.valueOf(e.getCause()));
    }

    @Test
    public void thatAFanOutOnAPoolThatWouldRunAChildWalkInlineEndsInsteadOfHanging() throws Exception {
        // the walk of S runs on the pool's thread; the walk of a, submitted from it, would run inline and wait at E
        // for b, which its own thread would only start afterwards
        Saga saga = Saga.start("caller-runs-fan").linkTo("a", "b")
                .id("a").adapter("ok").linkToEnd()
                .id("b").adapter("ok").linkToEnd()
                .end();
        SagaHandoffControl control = executeWithin(saga, 3_000);
        CompletionException e = expectThrows(CompletionException.class,
                () -> control.getCompletionFuture().orTimeout(3, TimeUnit.SECONDS).join());
        assertTrue(e.getCause() instanceof RejectedExecutionException, String.valueOf(e.getCause()));
        long deadline = System.currentTimeMillis() + 2_000;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            MemoryLogs.sleep(10);
        }
        assertEquals(pool.getActiveCount(), 0);
    }

    @Test
    public void thatALinearSagaStillRunsOnACallerRunsPool() throws Exception {
        Saga saga = Saga.start("caller-runs-linear").linkTo("a").id("a").adapter("ok").linkTo("b").id("b").adapter("ok").linkToEnd().end();
        SagaHandoffControl control = executeWithin(saga, 3_000);
        control.getCompletionFuture().orTimeout(3, TimeUnit.SECONDS).join();
    }
}
