package no.cantara.sagalog;

import no.cantara.sagalog.memory.MemorySagaLogPool;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * Shutdown, connect and release contracts of {@link AbstractSagaLogPool}, with a pool whose connect and close can be
 * made to block or fail.
 */
public class AbstractSagaLogPoolLifecycleTest {

    /**
     * A log id with a chosen hash code, so that two different ids can share a hash bin.
     */
    record TestLogId(String clusterInstanceId, String logName, int hash) implements SagaLogId {
        @Override
        public String getClusterInstanceId() {
            return clusterInstanceId;
        }

        @Override
        public String getLogName() {
            return logName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestLogId that && clusterInstanceId.equals(that.clusterInstanceId) && logName.equals(that.logName);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class TestLog implements SagaLog {
        final SagaLogId id;
        final Exception closeFailure;
        final AtomicInteger closed = new AtomicInteger();

        TestLog(SagaLogId id, Exception closeFailure) {
            this.id = id;
            this.closeFailure = closeFailure;
        }

        @Override
        public SagaLogId id() {
            return id;
        }

        @Override
        public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> truncate(SagaLogEntryId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> truncate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<SagaLogEntry> readIncompleteSagas() {
            return Stream.empty();
        }

        @Override
        public Stream<SagaLogEntry> readEntries(String executionId) {
            return Stream.empty();
        }

        @Override
        public String toString(SagaLogEntryId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SagaLogEntryId fromString(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] toBytes(SagaLogEntryId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SagaLogEntryId fromBytes(byte[] idBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() throws Exception {
            closed.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    static final class TestPool extends AbstractSagaLogPool {
        final Map<SagaLogId, AtomicInteger> connectsById = new ConcurrentHashMap<>();
        final List<TestLog> logs = new CopyOnWriteArrayList<>();
        volatile Function<SagaLogId, TestLog> connector = id -> new TestLog(id, null);

        TestPool() {
            super("test-instance");
        }

        @Override
        protected SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException {
            connectsById.computeIfAbsent(logId, id -> new AtomicInteger()).incrementAndGet();
            TestLog log = connector.apply(logId);
            logs.add(log);
            return log;
        }

        @Override
        protected boolean deleteExternal(SagaLogId logId) {
            return true;
        }

        @Override
        public SagaLogId idFor(String clusterInstanceId, String logName) {
            return new TestLogId(clusterInstanceId, logName, Objects.hash(clusterInstanceId, logName));
        }

        @Override
        public Set<SagaLogId> clusterWideLogIds() {
            return instanceLocalLogIds();
        }

        int connects(SagaLogId id) {
            AtomicInteger count = connectsById.get(id);
            return count == null ? 0 : count.get();
        }
    }

    // sl-1

    @Test
    public void thatShutdownClosesEveryLogAndThrowsTheFirstFailureWithLaterOnesSuppressed() {
        TestPool pool = new TestPool();
        IllegalStateException first = new IllegalStateException("close a failed");
        IllegalStateException second = new IllegalStateException("close c failed");
        SagaLogId a = pool.idFor("x", "a");
        SagaLogId b = pool.idFor("x", "b");
        SagaLogId c = pool.idFor("x", "c");
        pool.connector = id -> new TestLog(id, id.equals(a) ? first : id.equals(c) ? second : null);
        TestLog logA = (TestLog) pool.connect(a);
        TestLog logB = (TestLog) pool.connect(b);
        TestLog logC = (TestLog) pool.connect(c);

        RuntimeException thrown = expectThrows(RuntimeException.class, pool::shutdown);

        assertEquals(logA.closed.get(), 1);
        assertEquals(logB.closed.get(), 1, "a log after a failing one must still be closed");
        assertEquals(logC.closed.get(), 1);
        assertTrue(thrown == first || thrown == second, "the first failure is thrown: " + thrown);
        RuntimeException other = thrown == first ? second : first;
        assertEquals(List.of(thrown.getSuppressed()), List.of(other), "the later failure is suppressed");
        assertTrue(pool.delete(a), "the log is no longer in the pool after shutdown");
    }

    @Test
    public void thatShutdownWrapsACheckedCloseFailure() {
        TestPool pool = new TestPool();
        Exception checked = new Exception("checked close failure");
        pool.connector = id -> new TestLog(id, checked);
        SagaLogId a = pool.idFor("x", "a");
        pool.connect(a);
        RuntimeException thrown = expectThrows(RuntimeException.class, pool::shutdown);
        assertSame(thrown.getCause(), checked);
        assertTrue(pool.delete(a), "the log is no longer in the pool after shutdown");
    }

    // sl-2

    @Test
    public void thatConcurrentConnectsOfTheSameIdConnectOnceAndGetTheSameLog() throws Exception {
        TestPool pool = new TestPool();
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pool.connector = id -> {
            connecting.countDown();
            await(proceed);
            return new TestLog(id, null);
        };
        SagaLogId a = pool.idFor("x", "a");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CyclicBarrier start = new CyclicBarrier(4);
            List<Future<SagaLog>> results = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return pool.connect(a);
                }));
            }
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            Thread.sleep(50); // let the others reach the connect
            proceed.countDown();
            SagaLog first = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<SagaLog> result : results) {
                assertSame(result.get(5, TimeUnit.SECONDS), first);
            }
            assertEquals(pool.connects(a), 1);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    @Test
    public void thatASlowConnectOfOneIdDoesNotBlockAnotherIdInTheSameHashBin() throws Exception {
        TestPool pool = new TestPool();
        SagaLogId slow = new TestLogId("x", "slow", 42);
        SagaLogId fast = new TestLogId("x", "fast", 42); // same hash: same bin of the pool's map
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pool.connector = id -> {
            if (id.equals(slow)) {
                connecting.countDown();
                await(proceed);
            }
            return new TestLog(id, null);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SagaLog> slowConnect = executor.submit(() -> pool.connect(slow));
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            Future<SagaLog> fastConnect = executor.submit(() -> pool.connect(fast));
            try {
                assertEquals(fastConnect.get(2, TimeUnit.SECONDS).id(), fast);
            } catch (TimeoutException e) {
                fail("connect of another id waited for a slow connect");
            }
            proceed.countDown();
            assertEquals(slowConnect.get(5, TimeUnit.SECONDS).id(), slow);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    /**
     * A log held by another process stays available, but behind the others: tryAcquire hands out the free logs instead
     * of taking the held one again and again.
     */
    @Test
    public void thatALogHeldElsewhereDoesNotKeepTheFreeLogsFromBeingAcquired() {
        TestPool pool = new TestPool();
        SagaLogId a = pool.registerInstanceLocalIdFor("a");
        SagaLogId b = pool.registerInstanceLocalIdFor("b");
        SagaLogId c = pool.registerInstanceLocalIdFor("c");
        pool.connector = id -> {
            if (id.equals(a)) {
                throw new SagaLogBusyException("held by another process: " + id);
            }
            return new TestLog(id, null);
        };
        Set<SagaLogId> acquired = new java.util.LinkedHashSet<>();
        int busy = 0;
        for (int i = 0; i < 3; i++) {
            try {
                SagaLog log = pool.tryAcquire(new SagaLogOwner("owner-" + i));
                if (log != null) {
                    acquired.add(log.id());
                }
            } catch (SagaLogBusyException e) {
                busy++;
            }
        }
        assertEquals(acquired, Set.of(b, c), "both free logs are handed out within three attempts (" + busy + " busy)");
        assertEquals(busy, 1, "the held log is tried once, then goes behind the others");
        pool.shutdown();
    }

    @Test
    public void thatALogHeldElsewhereDoesNotKeepTheFreeLogsFromBeingAcquiredWithATimeout() throws InterruptedException {
        TestPool pool = new TestPool();
        SagaLogId a = pool.registerInstanceLocalIdFor("a");
        SagaLogId b = pool.registerInstanceLocalIdFor("b");
        pool.connector = id -> {
            if (id.equals(a)) {
                throw new SagaLogBusyException("held by another process: " + id);
            }
            return new TestLog(id, null);
        };
        expectThrows(SagaLogBusyException.class, () -> pool.tryAcquire(new SagaLogOwner("first"), 1, TimeUnit.SECONDS));
        SagaLog log = pool.tryAcquire(new SagaLogOwner("second"), 1, TimeUnit.SECONDS);
        assertNotNull(log);
        assertEquals(log.id(), b, "the free log is handed out next");
        pool.shutdown();
    }

    @Test
    public void thatAFailedConnectLeavesNoPlaceholderAndCanBeRetried() {
        TestPool pool = new TestPool();
        SagaLogId a = pool.idFor("x", "a");
        pool.connector = id -> {
            throw new SagaLogBusyException("busy: " + id);
        };
        expectThrows(SagaLogBusyException.class, () -> pool.connect(a));
        assertTrue(pool.delete(a), "a failed connect must not leave the id in the pool");
        pool.connector = id -> new TestLog(id, null);
        assertNotNull(pool.connect(a));
        assertEquals(pool.connects(a), 2);
        pool.shutdown();
    }

    @Test
    public void thatWaitersRetryWhenTheConnectTheyWaitedForFails() throws Exception {
        TestPool pool = new TestPool();
        SagaLogId a = pool.idFor("x", "a");
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger attempt = new AtomicInteger();
        pool.connector = id -> {
            if (attempt.incrementAndGet() == 1) {
                connecting.countDown();
                await(proceed);
                throw new SagaLogBusyException("first attempt fails");
            }
            return new TestLog(id, null);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SagaLog> first = executor.submit(() -> pool.connect(a));
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            Future<SagaLog> second = executor.submit(() -> pool.connect(a));
            Thread.sleep(50);
            proceed.countDown();
            try {
                first.get(5, TimeUnit.SECONDS);
                fail("the first connect must fail");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof SagaLogBusyException);
            }
            assertNotNull(second.get(5, TimeUnit.SECONDS));
            assertEquals(pool.connects(a), 2);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    // sl-3

    @Test
    public void thatReleasingAnIdNeverTakenThrowsIllegalStateException() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogId id = pool.registerInstanceLocalIdFor("never-taken");
        expectThrows(IllegalStateException.class, () -> pool.releaseOwnership(id));
        expectThrows(IllegalStateException.class, () -> pool.release(id));
        expectThrows(IllegalStateException.class, () -> pool.releaseOwnership(new SagaLogOwner("me"), id));
        assertNotNull(pool.tryAcquire(new SagaLogOwner("me")), "a failed release must not disturb availability");
    }

    @Test
    public void thatReleaseOwnershipWithOwnerReleasesTheOwnersLog() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogId id = pool.registerInstanceLocalIdFor("l1");
        SagaLogOwner me = new SagaLogOwner("me");
        assertNotNull(pool.tryTakeOwnership(me, id));
        pool.releaseOwnership(me, id);
        assertNotNull(pool.tryTakeOwnership(new SagaLogOwner("other"), id));
    }

    @Test
    public void thatReleaseOwnershipWithAnotherOwnerIsRefusedAndKeepsTheOwnership() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogId id = pool.registerInstanceLocalIdFor("l1");
        SagaLogOwner me = new SagaLogOwner("me");
        assertNotNull(pool.tryTakeOwnership(me, id));
        expectThrows(SagaLogAlreadyAquiredByOtherOwnerException.class, () -> pool.releaseOwnership(new SagaLogOwner("intruder"), id));
        assertEquals(pool.instanceLocalSagaLogOwnerships().iterator().next().getOwner(), me, "ownership is unchanged");
        assertNull(pool.tryTakeOwnership(new SagaLogOwner("other"), id), "the log is still held");
        assertNull(pool.tryAcquire(new SagaLogOwner("other")), "the log is still unavailable");
        pool.releaseOwnership(me, id);
        assertNotNull(pool.tryAcquire(new SagaLogOwner("other")));
    }

    @Test
    public void thatReleaseOwnershipWithOwnerTwiceThrowsIllegalStateException() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogOwner me = new SagaLogOwner("me");
        pool.registerInstanceLocalIdFor("l1");
        SagaLog log = pool.tryAcquire(me);
        pool.releaseOwnership(me, log.id());
        expectThrows(IllegalStateException.class, () -> pool.releaseOwnership(me, log.id()));
    }

    // sl-5

    @Test
    public void thatDeleteBeforeRemoveThrowsIllegalStateExceptionNamingTheId() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogId id = pool.registerInstanceLocalIdFor("still-connected");
        pool.connect(id);
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> pool.delete(id));
        assertTrue(e.getMessage().contains(id.toString()), e.getMessage());
        assertFalse(e.getMessage().contains("%s"), e.getMessage());
        pool.remove(id);
        assertTrue(pool.delete(id));
    }

    // sl-6

    @Test
    public void thatAShutDownPoolRefusesConnectAcquireAndTakeOwnership() {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        SagaLogId id = pool.registerInstanceLocalIdFor("l1");
        pool.shutdown();
        SagaLogOwner me = new SagaLogOwner("me");
        expectThrows(SagaPoolShutdownException.class, () -> pool.connect(id));
        expectThrows(SagaPoolShutdownException.class, () -> pool.tryAcquire(me));
        expectThrows(SagaPoolShutdownException.class, () -> pool.tryAcquire(me, 1, TimeUnit.MILLISECONDS));
        expectThrows(SagaPoolShutdownException.class, () -> pool.tryAcquire(me, 1L, TimeUnit.MILLISECONDS));
        expectThrows(SagaPoolShutdownException.class, () -> pool.tryTakeOwnership(me, id));
        expectThrows(SagaPoolShutdownException.class, () -> pool.tryTakeOwnership(me, id, 1, TimeUnit.MILLISECONDS));
        pool.shutdown(); // a second shutdown does nothing
    }

    @Test
    public void thatAConnectThatCompletesDuringShutdownIsClosedAndRefused() throws Exception {
        TestPool pool = new TestPool();
        SagaLogId a = pool.idFor("x", "a");
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pool.connector = id -> {
            connecting.countDown();
            await(proceed);
            return new TestLog(id, null);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SagaLog> connect = executor.submit(() -> pool.connect(a));
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            pool.shutdown();
            proceed.countDown();
            try {
                connect.get(5, TimeUnit.SECONDS);
                fail("a connect that completes after shutdown must be refused");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof SagaPoolShutdownException, String.valueOf(e.getCause()));
            }
            assertEquals(pool.logs.size(), 1);
            assertEquals(pool.logs.get(0).closed.get(), 1, "the late log is closed exactly once");
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    // sl-7

    @Test
    public void thatTryAcquireWithALongTimeoutWaitsForAReleasedLog() throws Exception {
        AbstractSagaLogPool pool = new MemorySagaLogPool("i");
        pool.registerInstanceLocalIdFor("l1");
        SagaLogOwner me = new SagaLogOwner("me");
        SagaLog log = pool.tryAcquire(me, 1L, TimeUnit.SECONDS);
        assertNotNull(log);
        assertNull(pool.tryAcquire(me, 1L, TimeUnit.MILLISECONDS));
        assertNull(pool.tryAcquire(me, Long.MIN_VALUE, TimeUnit.MILLISECONDS));
        pool.releaseOwnership(me, log.id());
        assertNotNull(pool.tryAcquire(me, Long.MAX_VALUE, TimeUnit.DAYS));
        pool.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
