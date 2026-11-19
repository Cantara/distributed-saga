package no.cantara.sagalog;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class AbstractSagaLogPool implements SagaLogPool {

    private final String clusterInstanceId;
    private final Set<SagaLogId> registeredInstanceLocalSagaLogIds = new CopyOnWriteArraySet();
    private final Map<SagaLogId, SagaLog> sagaLogByLogId = new ConcurrentHashMap<>();
    private final Map<SagaLogId, SagaLogOwnership> ownershipByLogId = new ConcurrentHashMap<>();
    private final Map<SagaLogId, Semaphore> exclusiveLockByLogId = new ConcurrentHashMap<>();
    private final BlockingDeque<SagaLogId> availableInstanceLocalIds = new LinkedBlockingDeque<>();

    protected AbstractSagaLogPool(String clusterInstanceId) {
        this.clusterInstanceId = clusterInstanceId;
    }

    protected abstract SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException;

    @Override
    public String getLocalClusterInstanceId() {
        return clusterInstanceId;
    }

    @Override
    public SagaLog connect(SagaLogId logId) throws SagaLogBusyException {
        return sagaLogByLogId.computeIfAbsent(logId, lId -> connectExternal(logId));
    }

    @Override
    public SagaLogId registerInstanceLocalIdFor(String logName) {
        SagaLogId sagaLogId = idFor(clusterInstanceId, logName);
        if (registeredInstanceLocalSagaLogIds.add(sagaLogId)) {
            availableInstanceLocalIds.add(sagaLogId);
        }
        return sagaLogId;
    }

    @Override
    public Set<SagaLogId> instanceLocalLogIds() {
        return Collections.unmodifiableSet(registeredInstanceLocalSagaLogIds);
    }

    @Override
    public Set<SagaLogOwnership> instanceLocalSagaLogOwnerships() {
        LinkedHashSet<SagaLogOwnership> ownerships = new LinkedHashSet<>(ownershipByLogId.values());
        ownerships.removeIf(ownership -> !clusterInstanceId.equals(ownership.getLogId().getClusterInstanceId()));
        return ownerships;
    }

    @Override
    public void remove(SagaLogId logId) {
        SagaLog sagaLog = sagaLogByLogId.remove(logId);
        if (sagaLog != null) {
            try {
                sagaLog.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public SagaLog tryAcquire(SagaLogOwner owner) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        while (true) {
            SagaLogId sagaLogId = availableInstanceLocalIds.poll();
            if (sagaLogId == null) {
                return null; // no available saga-logs right now
            }
            SagaLog sagaLog = takeAvailable(owner, sagaLogId, () -> tryTakeOwnership(owner, sagaLogId));
            if (sagaLog != null) {
                return sagaLog;
            }
            // taken by tryTakeOwnership in the meantime; its holder makes it available again on release
        }
    }

    @FunctionalInterface
    private interface Take {
        SagaLog take();
    }

    private SagaLog takeAvailable(SagaLogOwner owner, SagaLogId sagaLogId, Take take) {
        boolean done = false;
        try {
            SagaLog sagaLog = take.take();
            done = true;
            return sagaLog;
        } finally {
            if (!done) {
                makeAvailable(sagaLogId, true); // failed to connect: keep it available for a later attempt
            }
        }
    }

    @Override
    public SagaLog tryAcquire(SagaLogOwner owner, int timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long remaining = deadline - System.nanoTime();
            SagaLogId sagaLogId = availableInstanceLocalIds.poll(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (sagaLogId == null) {
                return null; // timeout
            }
            SagaLog sagaLog = takeAvailable(owner, sagaLogId, () -> tryTakeOwnership(owner, sagaLogId));
            if (sagaLog != null) {
                return sagaLog;
            }
            // taken by tryTakeOwnership in the meantime; its holder makes it available again on release
        }
    }

    /**
     * Same as {@link #releaseOwnership(SagaLogId)}: a registered instance-local log becomes available again once it is
     * not owned.
     */
    @Override
    public void release(SagaLogId logId) {
        releaseOwnership(logId);
    }

    /**
     * A registered instance-local log is available (in the queue for tryAcquire) exactly when nobody owns it, and is
     * in the queue at most once.
     */
    private void makeAvailable(SagaLogId logId, boolean first) {
        if (!registeredInstanceLocalSagaLogIds.contains(logId)) {
            return;
        }
        synchronized (availableInstanceLocalIds) {
            Semaphore semaphore = exclusiveLockByLogId.get(logId);
            boolean owned = semaphore != null && semaphore.availablePermits() == 0;
            if (owned || availableInstanceLocalIds.contains(logId)) {
                return;
            }
            if (first) {
                availableInstanceLocalIds.addFirst(logId);
            } else {
                availableInstanceLocalIds.addLast(logId);
            }
        }
    }

    private SagaLog doAssignPoolLocalOwnership(SagaLogOwner owner, SagaLogId logId) {
        SagaLog sagaLog = connect(logId);
        SagaLogOwnership ownership = ownershipByLogId.computeIfAbsent(logId, id -> new SagaLogOwnership(owner, id, ZonedDateTime.now()));
        if (!owner.equals(ownership.getOwner())) {
            throw new SagaLogAlreadyAquiredByOtherOwnerException(String.format("SagaLogOwner %s was unable to acquire saga-log with id %s. Already owned by %s.", owner.getOwnerId(), logId, ownership.getOwner().getOwnerId()));
        }
        return sagaLog;
    }

    @Override
    public SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        Semaphore semaphore = exclusiveLockByLogId.computeIfAbsent(logId, id -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            return null; // lock unavailable
        }
        SagaLog sagaLog = null;
        try {
            if (semaphore.availablePermits() > 0) {
                throw new IllegalStateException("More than one permit available. Most likely the saga-log was released when no ownership or lock/permit is held.");
            }
            sagaLog = doAssignPoolLocalOwnership(owner, logId);
            availableInstanceLocalIds.remove(logId); // owned: not available until released
            return sagaLog;
        } finally {
            if (sagaLog == null) {
                exclusiveLockByLogId.get(logId).release();
            }
        }
    }

    @Override
    public SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        Semaphore semaphore = exclusiveLockByLogId.computeIfAbsent(logId, id -> new Semaphore(1));
        if (!semaphore.tryAcquire(timeout, unit)) {
            return null; // timeout
        }
        SagaLog sagaLog = null;
        try {
            if (semaphore.availablePermits() > 0) {
                throw new IllegalStateException("Acquired permit, but there are still permits available. Someone has released this saga-log-id when no ownership or lock/permit is held.");
            }
            sagaLog = doAssignPoolLocalOwnership(owner, logId);
            availableInstanceLocalIds.remove(logId); // owned: not available until released
            return sagaLog;
        } finally {
            if (sagaLog == null) {
                exclusiveLockByLogId.get(logId).release();
            }
        }
    }

    @Override
    public void releaseOwnership(SagaLogId logId) {
        try {
            ownershipByLogId.remove(logId);
        } finally {
            Semaphore semaphore = exclusiveLockByLogId.get(logId);
            if (semaphore.availablePermits() > 0) {
                throw new IllegalStateException("Lock already released");
            }
            semaphore.release();
        }
        makeAvailable(logId, false);
    }

    @Override
    public boolean delete(SagaLogId logId) {
        if (sagaLogByLogId.containsKey(logId)) {
            throw new RuntimeException("Saga-log with id %s must be removed from pool before it can be deleted.");
        }
        return deleteExternal(logId);
    }

    protected abstract boolean deleteExternal(SagaLogId logId);

    @Override
    public void shutdown() {
        for (SagaLog sagaLog : sagaLogByLogId.values()) {
            try {
                sagaLog.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        sagaLogByLogId.clear();
        ownershipByLogId.clear();
    }
}
