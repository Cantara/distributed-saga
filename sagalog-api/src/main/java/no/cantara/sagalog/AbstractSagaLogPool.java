package no.cantara.sagalog;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pool logic shared by every saga-log provider: connected logs, ownership, and the queue of available
 * instance-local logs. A provider implements {@link #connectExternal(SagaLogId)}, {@link #deleteExternal(SagaLogId)},
 * {@link #idFor(String, String)} and {@link #clusterWideLogIds()}.
 * <p>
 * A provider that overrides {@link #shutdown()} must call {@code super.shutdown()}: it marks the pool as shut down, so
 * that {@code connect}, {@code tryAcquire} and {@code tryTakeOwnership} throw {@link SagaPoolShutdownException}, and it
 * closes every log this class holds.
 */
public abstract class AbstractSagaLogPool implements SagaLogPool {

    /**
     * A connected log, or a connect in progress. Only the thread that put the slot in the map connects; other callers
     * for that id wait for it. Closing the log is claimed once, by {@link #remove}, {@link #shutdown}, or a connect
     * that finds the pool shut down.
     */
    private static final class Slot {
        final CompletableFuture<SagaLog> log = new CompletableFuture<>();
        final AtomicBoolean closeClaimed = new AtomicBoolean();

        /**
         * @return the connected log, or null if the connect failed or is still in progress
         */
        SagaLog connectedLogOrNull() {
            if (!log.isDone() || log.isCompletedExceptionally()) {
                return null;
            }
            return log.join();
        }
    }

    private final String clusterInstanceId;
    private final Set<SagaLogId> registeredInstanceLocalSagaLogIds = new CopyOnWriteArraySet<>();
    private final Map<SagaLogId, Slot> sagaLogByLogId = new ConcurrentHashMap<>();
    private final Map<SagaLogId, SagaLogOwnership> ownershipByLogId = new ConcurrentHashMap<>();
    private final Map<SagaLogId, Semaphore> exclusiveLockByLogId = new ConcurrentHashMap<>();
    private final BlockingDeque<SagaLogId> availableInstanceLocalIds = new LinkedBlockingDeque<>();
    private volatile boolean shutdown;

    protected AbstractSagaLogPool(String clusterInstanceId) {
        this.clusterInstanceId = clusterInstanceId;
    }

    /**
     * Opens the provider's storage for the log, and locks it against other processes if it can be shared. The pool
     * calls it for one id at most once at a time, and never while it holds a lock on its maps.
     *
     * @param logId the id of the log to open
     * @return the open log
     * @throws SagaLogBusyException if another process holds the log
     */
    protected abstract SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException;

    @Override
    public String getLocalClusterInstanceId() {
        return clusterInstanceId;
    }

    /**
     * {@inheritDoc}
     * <p>
     * One {@link #connectExternal(SagaLogId)} runs per id at a time, outside any lock on the pool's maps: other callers
     * for the same id wait for it and get the same log, and callers for other ids do not wait. If that connect fails,
     * its caller gets the failure, nothing is left in the pool, and each caller that waited tries to connect itself.
     *
     * @throws SagaPoolShutdownException if the pool is shut down, also when it is shut down during the connect
     */
    @Override
    public SagaLog connect(SagaLogId logId) throws SagaLogBusyException {
        while (true) {
            checkNotShutdown();
            Slot slot = sagaLogByLogId.get(logId);
            if (slot == null) {
                Slot mine = new Slot();
                slot = sagaLogByLogId.putIfAbsent(logId, mine);
                if (slot == null) {
                    return connectSlot(logId, mine);
                }
            }
            SagaLog sagaLog;
            try {
                sagaLog = slot.log.join();
            } catch (CompletionException | CancellationException e) {
                continue; // the connect this caller waited for failed and left the pool: try again
            }
            checkNotShutdown();
            return sagaLog;
        }
    }

    private SagaLog connectSlot(SagaLogId logId, Slot slot) {
        SagaLog sagaLog;
        try {
            sagaLog = connectExternal(logId);
        } catch (RuntimeException | Error e) {
            sagaLogByLogId.remove(logId, slot);
            slot.log.completeExceptionally(e);
            throw e;
        }
        slot.log.complete(sagaLog);
        if (shutdown) {
            // shutdown() does not close a connect in progress: the connecting thread closes what it opened
            SagaPoolShutdownException refused = new SagaPoolShutdownException(String.format("Saga-log pool was shut down while connecting saga-log with id %s.", logId));
            sagaLogByLogId.remove(logId, slot);
            if (slot.closeClaimed.compareAndSet(false, true)) {
                try {
                    sagaLog.close();
                } catch (Exception e) {
                    refused.addSuppressed(e);
                }
            }
            throw refused;
        }
        return sagaLog;
    }

    private void checkNotShutdown() {
        if (shutdown) {
            throw new SagaPoolShutdownException("Saga-log pool is shut down.");
        }
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
        Slot slot = sagaLogByLogId.get(logId);
        if (slot == null) {
            return;
        }
        SagaLog sagaLog;
        try {
            sagaLog = slot.log.join(); // a connect in progress: wait for it, then remove and close what it opened
        } catch (CompletionException | CancellationException e) {
            return; // the connect failed and left the pool: nothing was opened
        }
        if (sagaLogByLogId.remove(logId, slot) && slot.closeClaimed.compareAndSet(false, true)) {
            try {
                sagaLog.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    @Override
    public SagaLog tryAcquire(SagaLogOwner owner) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        checkNotShutdown();
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

    /**
     * {@inheritDoc}
     *
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    @Override
    public SagaLog tryAcquire(SagaLogOwner owner, int timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        return tryAcquire(owner, (long) timeout, unit);
    }

    /**
     * {@inheritDoc}
     *
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    @Override
    public SagaLog tryAcquire(SagaLogOwner owner, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        checkNotShutdown();
        long deadline = System.nanoTime() + Math.max(0, unit.toNanos(timeout));
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
     *
     * @deprecated use {@link #releaseOwnership(SagaLogOwner, SagaLogId)}, which checks the owner
     */
    @Deprecated
    @Override
    public void release(SagaLogId logId) {
        releaseWithoutOwnerCheck(logId);
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

    /**
     * {@inheritDoc}
     *
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    @Override
    public SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        checkNotShutdown();
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

    /**
     * {@inheritDoc}
     *
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    @Override
    public SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        checkNotShutdown();
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

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #releaseOwnership(SagaLogOwner, SagaLogId)}, which checks the owner
     */
    @Deprecated
    @Override
    public void releaseOwnership(SagaLogId logId) {
        releaseWithoutOwnerCheck(logId);
    }

    private void releaseWithoutOwnerCheck(SagaLogId logId) {
        try {
            ownershipByLogId.remove(logId);
        } finally {
            releaseLock(logId);
        }
        makeAvailable(logId, false);
    }

    @Override
    public void releaseOwnership(SagaLogOwner owner, SagaLogId logId) {
        Objects.requireNonNull(owner, "owner");
        SagaLogOwnership ownership = ownershipByLogId.get(logId);
        if (ownership == null) {
            throw new IllegalStateException(String.format("SagaLogOwner %s cannot release saga-log with id %s. It is not owned in this pool.", owner.getOwnerId(), logId));
        }
        if (!owner.equals(ownership.getOwner())) {
            throw new SagaLogAlreadyAquiredByOtherOwnerException(String.format("SagaLogOwner %s cannot release saga-log with id %s. It is owned by %s.", owner.getOwnerId(), logId, ownership.getOwner().getOwnerId()));
        }
        if (!ownershipByLogId.remove(logId, ownership)) {
            throw new IllegalStateException(String.format("SagaLogOwner %s cannot release saga-log with id %s. It was released concurrently.", owner.getOwnerId(), logId));
        }
        releaseLock(logId);
        makeAvailable(logId, false);
    }

    private void releaseLock(SagaLogId logId) {
        Semaphore semaphore = exclusiveLockByLogId.get(logId);
        if (semaphore == null) {
            throw new IllegalStateException(String.format("Saga-log with id %s is not owned: it was never taken in this pool.", logId));
        }
        if (semaphore.availablePermits() > 0) {
            throw new IllegalStateException("Lock already released");
        }
        semaphore.release();
    }

    @Override
    public boolean delete(SagaLogId logId) {
        if (sagaLogByLogId.containsKey(logId)) {
            throw new IllegalStateException(String.format("Saga-log with id %s must be removed from pool before it can be deleted.", logId));
        }
        return deleteExternal(logId);
    }

    /**
     * Deletes the provider's storage of the log. Called by {@link #delete(SagaLogId)} once the log is not in the pool.
     *
     * @param logId the id of the log to delete
     * @return true if the log's storage was deleted, false otherwise
     */
    protected abstract boolean deleteExternal(SagaLogId logId);

    /**
     * {@inheritDoc}
     * <p>
     * Marks the pool as shut down first, then closes every connected log, also when closing one of them fails. A
     * connect still in progress closes its own log when it completes, and throws {@link SagaPoolShutdownException}.
     * The pool's logs and ownerships are cleared even if a close fails. The first failure is thrown, with later ones
     * added as suppressed; a checked failure is wrapped in a {@code RuntimeException}.
     * <p>
     * A provider that overrides this method must call {@code super.shutdown()}.
     */
    @Override
    public void shutdown() {
        shutdown = true;
        RuntimeException failure = null;
        try {
            for (Map.Entry<SagaLogId, Slot> entry : sagaLogByLogId.entrySet()) {
                Slot slot = entry.getValue();
                SagaLog sagaLog = slot.connectedLogOrNull();
                if (sagaLog == null || !slot.closeClaimed.compareAndSet(false, true)) {
                    continue; // not connected (yet), or closed by another thread
                }
                try {
                    sagaLog.close();
                } catch (Exception e) {
                    RuntimeException translated = e instanceof RuntimeException re ? re
                            : new RuntimeException(String.format("Failed to close saga-log with id %s on shutdown.", entry.getKey()), e);
                    if (failure == null) {
                        failure = translated;
                    } else if (translated != failure) {
                        failure.addSuppressed(translated);
                    }
                }
            }
        } finally {
            sagaLogByLogId.clear();
            ownershipByLogId.clear();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
