package no.cantara.concurrent.futureselector;

import org.testng.annotations.Test;

import java.lang.reflect.Modifier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The interrupt contract of laundering, run-at-most-once, and the API hygiene of the module (design review fs-1, fs-3,
 * fs-5, fs-6, fs-9).
 */
public class FutureSelectorContractTest {

    @Test
    public void thatLaunderingAnInterruptedExceptionRestoresTheInterruptFlag() {
        AtomicReference<RuntimeException> laundered = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            laundered.set(Utils.launder(new InterruptedException("test")));
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        join(thread);
        assertTrue(laundered.get() instanceof InterruptedRuntimeException);
        assertTrue(interruptedAfter.get(), "launder must restore the interrupt flag that catching cleared");
    }

    @Test
    public void thatAnInterruptedSelectThrowsAndKeepsTheThreadInterrupted() throws Exception {
        assertInterruptedSelect(selector -> selector.select());
    }

    @Test
    public void thatAnInterruptedTimedSelectThrowsAndKeepsTheThreadInterrupted() throws Exception {
        assertInterruptedSelect(selector -> selector.select(1, TimeUnit.MINUTES));
    }

    private interface Select {
        void select(FutureSelector<String, String> selector);
    }

    private void assertInterruptedSelect(Select select) throws Exception {
        FutureSelector<String, String> selector = new FutureSelector<>();
        SelectableFuture<String> never = new SelectableFuture<>(() -> "never run");
        selector.add(never, "never");
        CountDownLatch selecting = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            selecting.countDown();
            try {
                select.select(selector);
            } catch (Throwable t) {
                thrown.set(t);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        assertTrue(selecting.await(5, TimeUnit.SECONDS));
        // wait until the thread blocks in the queue, then interrupt it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "the selecting thread did not block");
            Thread.onSpinWait();
        }
        thread.interrupt();
        join(thread);
        assertTrue(thrown.get() instanceof InterruptedRuntimeException, "thrown: " + thrown.get());
        assertTrue(interruptedAfter.get(), "the selecting thread must stay interrupted");
        assertTrue(selector.pending(), "an interrupted select selects nothing");
    }

    @Test
    public void thatAFutureRunsItsTaskAtMostOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SelectableFuture<Integer> future = new SelectableFuture<>(calls::incrementAndGet);
        future.run();
        future.run();
        assertEquals(calls.get(), 1);
        assertEquals(future.get(), Integer.valueOf(1));
    }

    @Test
    public void thatAFutureRunConcurrentlyRunsItsTaskOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        SelectableFuture<Integer> future = new SelectableFuture<>(calls::incrementAndGet);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.run();
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) {
            join(thread);
        }
        assertEquals(calls.get(), 1);
    }

    @Test
    public void thatARunnableFutureRunsItsTaskAtMostOnce() {
        AtomicInteger calls = new AtomicInteger();
        SelectableFuture<String> future = new SelectableFuture<>(calls::incrementAndGet, "done");
        future.run();
        future.run();
        assertEquals(calls.get(), 1);
        assertEquals(future.join(), "done");
    }

    @Test
    public void thatSelectionFieldsAreFinal() throws Exception {
        assertTrue(Modifier.isFinal(Selection.class.getField("future").getModifiers()));
        assertTrue(Modifier.isFinal(Selection.class.getField("control").getModifiers()));
    }

    @Test
    public void thatUtilsCannotBeInstantiated() {
        assertEquals(Utils.class.getConstructors().length, 0, "Utils is a static holder");
    }

    @Test
    public void thatTheCorrectlySpelledExecutorSubmitsSelectableFutures() throws Exception {
        SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            SelectableFuture<String> future = pool.submit(() -> "done");
            FutureSelector<String, String> selector = new FutureSelector<>();
            selector.add(future, "c");
            Selection<String, String> selection = selector.select(5, TimeUnit.SECONDS);
            assertNotNull(selection);
            assertSame(selection.future, future);
            assertEquals(future.get(), "done");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void thatTheOldExecutorNameStillWorksAndIsTheNewType() throws Exception {
        SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            assertTrue(SelectableThreadPoolExecutor.class.isInstance(pool));
            assertTrue(SelectableThreadPoolExectutor.class.isAnnotationPresent(Deprecated.class));
            SelectableFuture<String> future = pool.submit(() -> "done");
            assertEquals(future.get(5, TimeUnit.SECONDS), "done");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void thatASelectedFutureIsDoneBeforeItIsSelected() throws Exception {
        FutureSelector<String, String> selector = new FutureSelector<>();
        SelectableFuture<String> future = new SelectableFuture<>(() -> "x");
        selector.add(future, "c");
        assertFalse(future.isDone());
        CompletableFuture.runAsync(future);
        assertTrue(selector.select(5, TimeUnit.SECONDS).future.isDone());
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertFalse(thread.isAlive(), "thread did not end");
    }
}
