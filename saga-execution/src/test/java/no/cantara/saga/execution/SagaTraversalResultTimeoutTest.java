package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.concurrent.futureselector.TimeoutRuntimeException;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertTrue;

/**
 * {@link SagaTraversalResult#waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(long, TimeUnit)} gives up after
 * its timeout while a walk is still running, as its Javadoc says, and so does a second caller waiting at the same time.
 */
public class SagaTraversalResultTimeoutTest {

    private static final Saga SAGA = Saga.start("hangs").linkTo("a")
            .id("a").adapter("a").linkToEnd()
            .end();

    private SelectableThreadPoolExecutor pool;
    private ExecutorService callers;
    private CountDownLatch release;

    @BeforeMethod
    public void createPools() {
        release = new CountDownLatch(1);
        pool = new SelectableThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        callers = Executors.newFixedThreadPool(2);
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPools() {
        release.countDown();
        pool.shutdownNow();
        callers.shutdownNow();
    }

    private SagaHandoffControl startSagaThatHangsInA() {
        AdapterLoader adapters = new AdapterLoader().register(new Adapter<>(String.class, "a", (i, d) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "a";
        }));
        return new SagaExecution(MemoryLogs.newLog(), pool, SAGA, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
    }

    private CompletableFuture<Throwable> waitInAnotherThread(SagaHandoffControl control) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                control.getTraversalResult().waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(200, TimeUnit.MILLISECONDS);
                return null;
            } catch (Throwable t) {
                return t;
            }
        }, callers);
    }

    @Test
    public void thatWaitingForAWalkThatHangsTimesOut() {
        SagaHandoffControl control = startSagaThatHangsInA();
        Throwable thrown = waitInAnotherThread(control).orTimeout(10, TimeUnit.SECONDS).join();
        assertTrue(thrown instanceof TimeoutRuntimeException, "timed out, not " + thrown);
    }

    @Test
    public void thatTwoCallersWaitingAtOnceBothTimeOut() {
        SagaHandoffControl control = startSagaThatHangsInA();
        CompletableFuture<Throwable> first = waitInAnotherThread(control);
        CompletableFuture<Throwable> second = waitInAnotherThread(control);
        assertTrue(first.orTimeout(10, TimeUnit.SECONDS).join() instanceof TimeoutRuntimeException, "the first caller timed out");
        assertTrue(second.orTimeout(10, TimeUnit.SECONDS).join() instanceof TimeoutRuntimeException, "the second caller timed out");
    }

    @Test
    public void thatWaitingStillSucceedsWhenTheWalksEndInTime() {
        SagaHandoffControl control = startSagaThatHangsInA();
        release.countDown();
        control.getTraversalResult().waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
    }
}
