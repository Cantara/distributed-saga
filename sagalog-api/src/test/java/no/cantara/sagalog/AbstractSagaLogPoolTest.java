package no.cantara.sagalog;

import no.cantara.sagalog.memory.MemorySagaLogPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class AbstractSagaLogPoolTest {

    AbstractSagaLogPool pool;

    @BeforeMethod
    public void setup() {
        this.pool = new MemorySagaLogPool("UnitTestInstance01");
    }

    @AfterMethod
    public void teardown() {
        pool.shutdown();
    }

    @Test
    public void thatTryAcquireGetNullWhenPoolIsEmpty() {
        assertNull(pool.tryAcquire(new SagaLogOwner("myself")));
    }

    @Test
    public void thatTryAcquireGetTheOnlyAvailableSagaLog() {
        SagaLogId logId = pool.registerInstanceLocalIdFor("a-saga-log-id");
        SagaLog sagaLog = pool.tryAcquire(new SagaLogOwner("myself"));
        assertNotNull(sagaLog);
        assertEquals(sagaLog.id(), logId);
        assertNull(pool.tryAcquire(new SagaLogOwner("myself")));
    }

    @Test
    public void thatTryAcquireGetAllTheAvailableSagaLogs() {
        SagaLogId logId1 = pool.registerInstanceLocalIdFor("a-saga-log-id");
        SagaLogId logId2 = pool.registerInstanceLocalIdFor("another-saga-log-id");
        SagaLog sagaLog1 = pool.tryAcquire(new SagaLogOwner("myself"));
        assertNotNull(sagaLog1);
        SagaLog sagaLog2 = pool.tryAcquire(new SagaLogOwner("myself"));
        assertNotNull(sagaLog2);
        assertEquals(Set.of(logId1, logId2), Set.of(sagaLog1.id(), sagaLog2.id()));
        assertNull(pool.tryAcquire(new SagaLogOwner("myself")));
    }

    @Test
    public void thatReleaseDoesReturnSagaLogToPool() {
        pool.registerInstanceLocalIdFor("a-saga-log-id");
        SagaLog sagaLog = pool.tryAcquire(new SagaLogOwner("myself"));
        assertNotNull(sagaLog);
        assertNull(pool.tryAcquire(new SagaLogOwner("myself")));
        pool.release(sagaLog.id());
        assertNotNull(pool.tryAcquire(new SagaLogOwner("myself")));
    }

    @Test
    public void thatTryAcquireWithTimeoutDoesTimeOutWhenNoAvailableLogs() throws InterruptedException {
        assertNull(pool.tryAcquire(new SagaLogOwner("myself"), 1, TimeUnit.MILLISECONDS));
    }

    @Test
    public void thatTryAcquireWithTimeoutCanAcquireAvailableSagaLog() throws InterruptedException {
        pool.registerInstanceLocalIdFor("a-saga-log-id");
        assertNotNull(pool.tryAcquire(new SagaLogOwner("myself"), 1, TimeUnit.MILLISECONDS));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void thatAcquireAnAlreadyOwnedLogWillThrowException() {
        SagaLogId logId = pool.registerInstanceLocalIdFor("already-owned");
        assertNotNull(pool.tryTakeOwnership(new SagaLogOwner("you"), logId));
        pool.tryAcquire(new SagaLogOwner("me"));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void thatAcquireWithTimeoutAnAlreadyOwnedLogWillThrowException() throws InterruptedException {
        SagaLogId logId = pool.registerInstanceLocalIdFor("already-owned");
        assertNotNull(pool.tryTakeOwnership(new SagaLogOwner("you"), logId));
        pool.tryAcquire(new SagaLogOwner("me"), 1, TimeUnit.MILLISECONDS);
    }

    @Test
    public void thatTakingOwnershipOfAnAlreadyOwnedLogIsNotAllowed() {
        SagaLogId logId = pool.registerInstanceLocalIdFor("already-owned");
        assertNotNull(pool.tryTakeOwnership(new SagaLogOwner("you"), logId));
        assertNull(pool.tryTakeOwnership(new SagaLogOwner("me"), logId));
    }

    @Test
    public void thatTakingOwnershipWithTimeoutOfAnAlreadyOwnedLogIsNotAllowed() throws InterruptedException {
        SagaLogId logId = pool.registerInstanceLocalIdFor("already-owned");
        assertNotNull(pool.tryTakeOwnership(new SagaLogOwner("you"), logId));
        assertNull(pool.tryTakeOwnership(new SagaLogOwner("me"), logId, 1, TimeUnit.MILLISECONDS));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void thatReleaseMoreThanOnceThrowsException() {
        pool.registerInstanceLocalIdFor("more-than-one-release-id");
        SagaLog sagaLog = pool.tryAcquire(new SagaLogOwner("me"));
        pool.release(sagaLog.id());
        pool.release(sagaLog.id());
    }

    @Test
    public void thatReleasingAnUnregisteredDoesNotMakeItEligibleForAcquiring() {
        SagaLog notRegisteredLog = pool.tryTakeOwnership(new SagaLogOwner("me"), pool.idFor(pool.getLocalClusterInstanceId(), "not-registered-log"));
        pool.release(notRegisteredLog.id());
        assertNull(pool.tryAcquire(new SagaLogOwner("me")));
    }

    @Test
    public void thatOwnedButNotAcquiredNonRegisteredSagaLogCannotBeRemoved() {
        SagaLog sagaLog = pool.tryTakeOwnership(new SagaLogOwner("me"), pool.idFor("cluster-instance-A", "external-1"));
        pool.release(sagaLog.id());
        pool.remove(sagaLog.id());
    }

    @Test
    public void thatOwnedButNotAcquiredRegisteredSagaLogCanBeRemoved() {
        SagaLog sagaLog = pool.tryTakeOwnership(new SagaLogOwner("me"), pool.registerInstanceLocalIdFor("registered-1"));
        pool.release(sagaLog.id());
        pool.remove(sagaLog.id());
    }

    @Test
    public void thatConnectDoesProvideWorkingSagaLog() {
        SagaLogId logId = pool.registerInstanceLocalIdFor("testlog");
        SagaLog sagaLog = pool.connect(logId);
        try {
            sagaLog.truncate().join();
            CompletableFuture<SagaLogEntry> w1 = sagaLog.write(sagaLog.builder().startAction("e1", "n1"));
            CompletableFuture<SagaLogEntry> w2 = sagaLog.write(sagaLog.builder().startAction("e1", "n2"));
            CompletableFuture<SagaLogEntry> w3 = sagaLog.write(sagaLog.builder().endAction("e1", "n2", "{}"));
            w1.join();
            w2.join();
            w3.join();
            assertEquals(sagaLog.readIncompleteSagas().count(), 3);
            sagaLog.truncate().join();
        } finally {
            pool.remove(logId);
        }
    }

    @Test
    public void thatAcquireAndReleaseOwnershipWorks() {
        SagaLogId sagaLogId = pool.registerInstanceLocalIdFor("l1");
        SagaLog l1o1 = pool.tryTakeOwnership(new SagaLogOwner("o1"), sagaLogId);
        assertNotNull(l1o1);
        pool.releaseOwnership(sagaLogId);
        SagaLog l1o2 = pool.tryTakeOwnership(new SagaLogOwner("o2"), sagaLogId);
        assertTrue(l1o1 == l1o2);
        pool.releaseOwnership(sagaLogId);
        SagaLog l1o3 = pool.tryTakeOwnership(new SagaLogOwner("o2"), sagaLogId);
        assertTrue(l1o1 == l1o3);
        pool.releaseOwnership(sagaLogId);
    }

    @Test
    public void thatInstanceLocalSagaLogOwnershipsWorks() {
        SagaLogId e1 = pool.idFor("otherInstance", "e1");
        SagaLogId e2 = pool.idFor("otherInstance", "e2");
        pool.connect(e1);
        pool.connect(e2);
        pool.remove(e1);
        pool.remove(e2);

        SagaLogId l1 = pool.registerInstanceLocalIdFor("l1");
        SagaLogId l2 = pool.registerInstanceLocalIdFor("l2");
        SagaLogId l3 = pool.registerInstanceLocalIdFor("l3");
        pool.tryTakeOwnership(new SagaLogOwner("o1"), l1);
        pool.tryTakeOwnership(new SagaLogOwner("o1"), l2);
        pool.tryTakeOwnership(new SagaLogOwner("o2"), l3);

        Set<SagaLogOwnership> sagaLogOwnerships = pool.instanceLocalSagaLogOwnerships();
        assertEquals(sagaLogOwnerships.size(), 3);

        pool.releaseOwnership(l1);
        pool.releaseOwnership(l2);
        pool.releaseOwnership(l3);

        Set<SagaLogOwnership> sagaLogOwnerships2 = pool.instanceLocalSagaLogOwnerships();
        assertEquals(sagaLogOwnerships2.size(), 0);

        Set<SagaLogId> logIds = pool.instanceLocalLogIds();
        assertEquals(logIds, Set.of(l1, l2, l3));
    }
}
