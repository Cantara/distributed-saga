package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.fail;

/**
 * An executor whose {@code submit} throws an {@link Error} (an out-of-memory error, say) must still let the saga
 * complete: the walk that was never submitted ends its count, so the saga fails with that error instead of staying
 * open for ever. Inside a running walk, the walks that never started are ended when the walk fails; the first walk,
 * which {@code executeSaga} submits on the caller's thread, has no walk to clean up after it, so {@code submit} itself
 * must end its count.
 */
public class SagaExecutionSubmitErrorTest {

    static final class SubmitError extends Error {
        SubmitError() {
            super("submit failed");
        }
    }

    /**
     * Throws on the given submit (1 = the first walk, from {@code executeSaga}).
     */
    static final class FailsOnSubmit extends SelectableThreadPoolExecutor {
        final AtomicInteger submits = new AtomicInteger();
        final SubmitError error = new SubmitError();
        final int failing;

        FailsOnSubmit(int failing) {
            super(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            this.failing = failing;
        }

        @Override
        public <T> SelectableFuture<T> submit(Callable<T> task) {
            if (submits.incrementAndGet() == failing) {
                throw error;
            }
            return super.submit(task);
        }
    }

    static final Saga FORK = Saga.start("fork").linkTo("a", "b")
            .id("a").adapter("ok").linkToEnd()
            .id("b").adapter("ok").linkToEnd()
            .end();
    static final AdapterLoader ADAPTERS = new AdapterLoader().register(new Adapter<>(String.class, "ok", (i, d) -> "ok"));

    @Test
    public void thatAnErrorFromTheFirstSubmitCompletesTheSagaBeforeItReachesTheCaller() {
        FailsOnSubmit pool = new FailsOnSubmit(1);
        try {
            java.util.List<SagaHandoffResult> completed = new java.util.concurrent.CopyOnWriteArrayList<>();
            SubmitError thrown = org.testng.Assert.expectThrows(SubmitError.class, () ->
                    new SagaExecution(MemoryLogs.newLog(), pool, FORK, ADAPTERS)
                            .executeSaga(UUID.randomUUID().toString(), "{}", false, completed::add));
            assertSame(thrown, pool.error, "the error reaches the caller of executeSaga");
            assertEquals(completed.size(), 1, "the saga completed, failed, before the error reached the caller");
            assertSame(completed.get(0).getFailureCause(), pool.error);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void thatAnErrorFromSubmittingABranchFailsTheSagaAndEndsItsWalks() {
        FailsOnSubmit pool = new FailsOnSubmit(2);
        try {
            SagaHandoffControl control = new SagaExecution(MemoryLogs.newLog(), pool, FORK, ADAPTERS)
                    .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> {
                    });

            Throwable failure = null;
            try {
                control.getCompletionFuture().copy().orTimeout(10, TimeUnit.SECONDS).join();
                fail("the saga succeeded although a walk could not be submitted");
            } catch (CompletionException e) {
                failure = e.getCause();
            }
            if (failure instanceof TimeoutException) {
                fail("the saga stayed open: the walk that was never submitted did not end its count; state: " + control.getState());
            }
            assertSame(failure, pool.error, "the saga fails with the submit's error: " + failure);
            assertEquals(control.getState().getWalks(), 0, "no walk is left: " + control.getState());
        } finally {
            pool.shutdownNow();
        }
    }
}
