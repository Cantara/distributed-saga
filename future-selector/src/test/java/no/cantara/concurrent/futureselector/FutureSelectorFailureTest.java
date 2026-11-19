package no.cantara.concurrent.futureselector;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * A future that completes exceptionally is selected like one that completes normally.
 */
public class FutureSelectorFailureTest {

    private SelectableThreadPoolExectutor pool;

    @BeforeMethod
    public void createPool() {
        pool = new SelectableThreadPoolExectutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    public void thatAFailedFutureIsSelected() throws Exception {
        FutureSelector<String, String> selector = new FutureSelector<>();
        selector.add(pool.submit(() -> {
            throw new IllegalStateException("failed");
        }), "failing");

        // select() would block for ever if the failed future were not selectable
        Selection<String, String> selected = CompletableFuture.supplyAsync(selector::select).get(5, TimeUnit.SECONDS);
        assertEquals(selected.control, "failing");
        assertTrue(selected.future.isCompletedExceptionally());
        ExecutionException e = expectThrows(ExecutionException.class, () -> selected.future.get());
        assertTrue(e.getCause() instanceof IllegalStateException);
        assertFalse(selector.pending());
    }

    @Test
    public void thatAFutureThatAlreadyFailedIsSelected() throws Exception {
        SelectableFuture<String> failed = pool.submit(() -> {
            throw new IllegalStateException("failed");
        });
        expectThrows(ExecutionException.class, failed::get); // done before it is added

        FutureSelector<String, String> selector = new FutureSelector<>();
        selector.add(failed, "failed");
        Selection<String, String> selected = selector.select(5, TimeUnit.SECONDS);
        assertSame(selected.future, failed);
    }

    @Test
    public void thatFailedAndSuccessfulFuturesAreSelectedInCompletionOrder() throws Exception {
        FutureSelector<String, String> selector = new FutureSelector<>();
        selector.add(pool.submit(() -> {
            Thread.sleep(200);
            return "late";
        }), "late");
        selector.add(pool.submit(() -> {
            throw new IllegalStateException("early failure");
        }), "early");

        assertEquals(selector.select(5, TimeUnit.SECONDS).control, "early");
        assertEquals(selector.select(5, TimeUnit.SECONDS).control, "late");
        assertFalse(selector.pending());
    }

    @Test
    public void thatATimedOutSelectSelectsNothing() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FutureSelector<String, String> selector = new FutureSelector<>();
        selector.add(pool.submit(() -> {
            release.await();
            return "done";
        }), "slow");

        assertEquals(selector.select(20, TimeUnit.MILLISECONDS), null);
        assertTrue(selector.pending(), "a timed-out select must not count as a selection");

        release.countDown();
        assertEquals(selector.select(5, TimeUnit.SECONDS).control, "slow");
        assertFalse(selector.pending());
    }
}
