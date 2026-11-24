package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * A node with several parents (a join) holds no pool thread while it waits for them: the parent that ends last starts
 * it. So sagas whose branches outnumber the pool's threads still complete, and still roll back.
 * <p>
 * The saga is {@code S -> {a, b} -> j -> E}. Step b waits on a barrier until both sagas are in it, so both pool
 * threads are taken by the walks that run b while both a's are still queued. A join that waited on its thread would
 * keep them queued for ever.
 */
public class SagaExecutionJoinTest {

    private static final Saga SAGA = Saga.start("fork-join").linkTo("a", "b")
            .id("a").adapter("a").linkTo("j")
            .id("b").adapter("b").linkTo("j")
            .id("j").adapter("j").linkToEnd()
            .end();

    private SelectableThreadPoolExecutor pool;

    @BeforeMethod
    public void createPool() {
        pool = new SelectableThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        pool.shutdownNow();
    }

    private static AdapterLoader adapters(CyclicBarrier bothInB, boolean abortAtJ, List<String> compensated) {
        return new AdapterLoader()
                .register(new Adapter<>(String.class, "a", (i, d) -> "a", (i, o) -> compensated.add("a")))
                .register(new Adapter<>(String.class, "b", (i, d) -> {
                    try {
                        bothInB.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        throw new IllegalStateException(e);
                    }
                    return "b";
                }, (i, o) -> compensated.add("b")))
                .register(new Adapter<>(String.class, "j", (i, d) -> {
                    if (abortAtJ) {
                        throw new AbortSagaException("abort at j");
                    }
                    return "j";
                }));
    }

    @Test
    public void thatSagasWaitingAtAJoinDoNotHoldThePoolThreadsTheirOtherBranchesNeed() {
        AdapterLoader adapters = adapters(new CyclicBarrier(2), false, new CopyOnWriteArrayList<>());
        SagaHandoffControl first = new SagaExecution(MemoryLogs.newLog(), pool, SAGA, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        SagaHandoffControl second = new SagaExecution(MemoryLogs.newLog(), pool, SAGA, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });

        first.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join();
        second.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    public void thatSagasThatAbortAtAJoinRollBackOnAPoolSmallerThanTheirBranches() {
        List<String> compensated = new CopyOnWriteArrayList<>();
        AdapterLoader adapters = adapters(new CyclicBarrier(2), true, compensated);
        SagaHandoffControl first = new SagaExecution(MemoryLogs.newLog(), pool, SAGA, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });
        SagaHandoffControl second = new SagaExecution(MemoryLogs.newLog(), pool, SAGA, adapters)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                });

        for (SagaHandoffControl control : List.of(first, second)) {
            CompletionException e = expectThrows(CompletionException.class,
                    () -> control.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join());
            assertFalse(e.getCause() instanceof TimeoutException, "the saga did not complete: " + e.getCause());
            assertTrue(e.getCause() instanceof AbortSagaException, "the saga rolled back: " + e.getCause());
        }
        assertEquals(compensated.stream().filter("a"::equals).count(), 2, "a compensated in both sagas: " + compensated);
        assertEquals(compensated.stream().filter("b"::equals).count(), 2, "b compensated in both sagas: " + compensated);
    }
}
