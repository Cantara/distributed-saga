package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * se-3: a failure in the rollback itself, before its backward walk starts, completes the saga.
 */
public class SagaExecutionRollbackFailureTest {

    private static final Saga SAGA = Saga.start("rollback-read").linkTo("a")
            .id("a").adapter("aborts").linkToEnd()
            .end();

    private static final AdapterLoader ADAPTERS = new AdapterLoader()
            .register(new Adapter<>(String.class, "aborts", (i, d) -> {
                throw new AbortSagaException("abort");
            }));

    private SelectableThreadPoolExectutor pool;

    @BeforeMethod
    public void createPool() {
        pool = new SelectableThreadPoolExectutor(4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @AfterMethod(alwaysRun = true)
    public void shutdownPool() {
        pool.shutdownNow();
    }

    /**
     * A saga log whose reads fail once an Abort entry was written, like a database connection lost during rollback.
     */
    static class ReadFailsAfterAbort implements SagaLog {
        final SagaLog delegate = MemoryLogs.newLog();
        final AtomicBoolean failReads = new AtomicBoolean();
        final RuntimeException failure = new IllegalStateException("log read failed (connection lost)");

        @Override
        public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
            if (builder.entryType() == SagaLogEntryType.Abort) {
                failReads.set(true);
            }
            return delegate.write(builder);
        }

        @Override
        public Stream<SagaLogEntry> readEntries(String executionId) {
            if (failReads.get()) {
                throw failure;
            }
            return delegate.readEntries(executionId);
        }

        @Override public SagaLogId id() { return delegate.id(); }
        @Override public CompletableFuture<Void> truncate(SagaLogEntryId id) { return delegate.truncate(id); }
        @Override public CompletableFuture<Void> truncate() { return delegate.truncate(); }
        @Override public Stream<SagaLogEntry> readIncompleteSagas() { return delegate.readIncompleteSagas(); }
        @Override public String toString(SagaLogEntryId id) { return delegate.toString(id); }
        @Override public SagaLogEntryId fromString(String id) { return delegate.fromString(id); }
        @Override public byte[] toBytes(SagaLogEntryId id) { return delegate.toBytes(id); }
        @Override public SagaLogEntryId fromBytes(byte[] idBytes) { return delegate.fromBytes(idBytes); }
        @Override public void close() throws Exception { delegate.close(); }
    }

    @Test
    public void thatAFailingReadOfTheLogInTheRollbackCompletesTheSaga() {
        ReadFailsAfterAbort log = new ReadFailsAfterAbort();
        AtomicInteger onComplete = new AtomicInteger();
        SagaHandoffControl control = new SagaExecution(log, pool, SAGA, ADAPTERS)
                .executeSaga(UUID.randomUUID().toString(), "{}", false, r -> onComplete.incrementAndGet());
        CompletionException e = expectThrows(CompletionException.class,
                () -> control.getCompletionFuture().orTimeout(3, TimeUnit.SECONDS).join());
        assertSame(e.getCause(), log.failure, "the saga fails with the read's failure");
        assertTrue(Arrays.stream(e.getCause().getSuppressed()).anyMatch(s -> s instanceof AbortSagaException),
                "the abort is suppressed on it");
        assertEquals(onComplete.get(), 1);
    }
}
