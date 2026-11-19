package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.Test;

import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SagaExecutionOnCompleteTest {

    private static final SelectableThreadPoolExectutor executorService = new SelectableThreadPoolExectutor(
            5, 20, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "on-complete-test-worker"),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static final AdapterLoader adapterLoader = new AdapterLoader()
            .register(new SagaAdapterGeneric())
            .register(new Adapter<>(Object.class, "abort", (sagaInput, dependeesOutput) -> {
                throw new AbortSagaException();
            }));

    private static final Saga EMPTY = Saga.start("empty").linkToEnd().end();
    private static final Saga ONE_ACTION = Saga.start("one").linkTo("A")
            .id("A").adapter(SagaAdapterGeneric.NAME).linkToEnd()
            .end();
    private static final Saga ABORTING = Saga.start("aborting").linkTo("A")
            .id("A").adapter("abort").linkToEnd()
            .end();

    private SagaLog newSagaLog() {
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> "no.cantara.sagalog.memory.MemorySagaLogInitializer".equals(c.type().getName()))
                .findFirst().orElseThrow().get();
        SagaLogPool pool = initializer.initialize(initializer.configurationOptionsAndDefaults());
        return pool.connect(pool.idFor(pool.getLocalClusterInstanceId(), "on-complete-" + UUID.randomUUID()));
    }

    private SagaHandoffControl execute(Saga saga, java.util.function.Consumer<SagaHandoffResult> onComplete) {
        return new SagaExecution(newSagaLog(), executorService, saga, adapterLoader)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, onComplete);
    }

    @Test
    public void thatOnCompleteHasReturnedBeforeCompletionFutureCompletes() {
        for (Saga saga : new Saga[]{EMPTY, ONE_ACTION}) {
            for (int i = 0; i < 50; i++) {
                AtomicBoolean onCompleteReturned = new AtomicBoolean(false);
                SagaHandoffControl control = execute(saga, r -> {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    onCompleteReturned.set(true);
                });
                SagaHandoffResult result = control.getCompletionFuture().join();
                assertTrue(result.isSuccess());
                assertTrue(onCompleteReturned.get(), saga.name + " run " + i);
            }
        }
    }

    @Test
    public void thatOnCompleteHasReturnedBeforeCompletionFutureCompletesAfterRollback() {
        AtomicBoolean onCompleteReturned = new AtomicBoolean(false);
        AtomicReference<SagaHandoffResult> seen = new AtomicReference<>();
        SagaHandoffControl control = execute(ABORTING, r -> {
            seen.set(r);
            onCompleteReturned.set(true);
        });
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
        assertTrue(e.getCause() instanceof AbortSagaException);
        assertTrue(onCompleteReturned.get());
        assertTrue(seen.get().isFailure());
        assertSame(seen.get().getFailureCause(), e.getCause());
    }

    @Test
    public void thatOnCompleteRunsOnAnExecutorThread() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        execute(EMPTY, r -> thread.set(Thread.currentThread())).getCompletionFuture().join();
        assertNotEquals(thread.get(), Thread.currentThread());
        assertEquals(thread.get().getName(), "on-complete-test-worker");
    }

    @Test
    public void thatAThrowingOnCompleteAfterSuccessCompletesWithSagaOnCompleteException() {
        IllegalStateException thrown = new IllegalStateException("truncate failed");
        SagaHandoffControl control = execute(ONE_ACTION, r -> {
            throw thrown;
        });
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
        assertTrue(e.getCause() instanceof SagaOnCompleteException);
        assertSame(e.getCause().getCause(), thrown);
        assertEquals(((SagaOnCompleteException) e.getCause()).getExecutionId(), control.getExecutionId());
    }

    @Test
    public void thatAThrowingOnCompleteAfterRollbackKeepsTheSagaFailure() {
        IllegalStateException thrown = new IllegalStateException("truncate failed");
        SagaHandoffControl control = execute(ABORTING, r -> {
            throw thrown;
        });
        CompletionException e = expectThrows(CompletionException.class, () -> control.getCompletionFuture().join());
        assertTrue(e.getCause() instanceof AbortSagaException);
        assertEquals(e.getCause().getSuppressed().length, 1);
        assertSame(e.getCause().getSuppressed()[0], thrown);
    }

    @Test
    public void thatANullOnCompleteIsAllowed() {
        SagaHandoffResult result = execute(ONE_ACTION, null).getCompletionFuture().join();
        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
    }
}
