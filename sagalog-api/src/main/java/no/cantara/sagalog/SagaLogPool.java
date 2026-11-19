package no.cantara.sagalog;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Opens saga logs, hands them out, and tracks who owns them. A pool is created by a provider's
 * {@link SagaLogInitializer#initialize(java.util.Map)}.
 * <p>
 * Ownership inside a pool is a lock per log: one owner at a time. A registered instance-local log is available to the
 * {@code tryAcquire} methods exactly when nobody owns it, and at most once. Across processes, a provider can also lock
 * the log's storage when it connects. All methods are safe to call from several threads.
 */
public interface SagaLogPool {

    /**
     * Get the cluster-instance-id used when registering and managing instance-local saga-logs or saga-log-ids.
     *
     * @return the instance-local cluster-id.
     */
    String getLocalClusterInstanceId();

    /**
     * Makes the id of any log, in this instance or another. It does not register, connect or own the log.
     *
     * @param clusterInstanceId the cluster instance id of the instance the log belongs to; not null
     * @param logName           the log's name within that instance; not null
     * @return the log id, equal to every other id made from the same two values
     */
    SagaLogId idFor(String clusterInstanceId, String logName);

    /**
     * Makes an id for the given log name in this instance ({@link #getLocalClusterInstanceId()}) and registers it as an
     * instance-local log: it is included by {@link #instanceLocalLogIds()} and becomes available to the
     * {@code tryAcquire} methods. Registering the same name again returns an equal id and changes nothing.
     *
     * @param logName the log's name; not null
     * @return the registered log id
     */
    SagaLogId registerInstanceLocalIdFor(String logName);

    /**
     * List all logIds cluster-wide that is relevant to a recovery procedure and available from this instance. This will
     * typically include all saga-logs in the cluster that might have incomplete entries. Saga-logs with a saga-log id
     * from this list that is not also in the instance-local id list should always be removed from the pool immediately
     * after recovery or other administrative use has completed, otherwise the saga-log could be blocked from being
     * used by other application cluster instances.
     *
     * @return the list of cluster-wide log ids.
     */
    Set<SagaLogId> clusterWideLogIds();

    /**
     * The ids registered with {@link #registerInstanceLocalIdFor(String)}: the logs of this instance, used by the
     * {@code tryAcquire} methods and by recovery. These logs are typically not removed from the pool after recovery.
     *
     * @return an unmodifiable view of the registered instance-local ids
     */
    Set<SagaLogId> instanceLocalLogIds();

    /**
     * List all saga-log ownerships currently registered with the pool. This is an unreliable snapshot of ownerships
     * typically only used for monitoring or health purposes.
     *
     * @return a snapshot of the current ownerships of instance-local logs; changing it does not change the pool
     */
    Set<SagaLogOwnership> instanceLocalSagaLogOwnerships();

    /**
     * Attempt to connect to the external saga-log resource associated with the given logId, which implies that this
     * pool will take exclusive ownership of such external resource. If successful, the ownership is awarded to this
     * pool, and the returned SagaLog is cached in the pool. In order to release ownership, the remove method should
     * be invoked on this pool. Connect will typically open a reusable connection or io-channel to the backing saga-log.
     * If the saga-log is already present in the pool, that log is returned immediately without registering or affecting
     * ownership. If the saga-log is already owned by another pool or external party, a SagaLogBusyException is thrown.
     *
     * @param logId the id of the log to connect to
     * @return the successfully connected SagaLog
     * @throws SagaLogBusyException      if the saga-log is busy due to this pool not being able to take exclusive
     *                                   ownership of the related external resource.
     * @throws SagaPoolShutdownException if the pool is shut down
     */
    SagaLog connect(SagaLogId logId) throws SagaLogBusyException;

    /**
     * Close and then remove (but does not release) the saga-log with the given logId from the pool. This will cause
     * subsequent calls to acquire on the same logId to re-connect to the log. Call it while you still own the log:
     * own it, remove it, then release it. Ownership is not changed by this method. Removing a log that is not in the
     * pool does nothing.
     *
     * @param logId the id of the log to close and remove
     */
    void remove(SagaLogId logId);

    /**
     * Takes the next available registered instance-local log, with ownership for the given owner, connecting it if
     * needed. Does not wait. Release it with {@link #releaseOwnership(SagaLogOwner, SagaLogId)} when the saga is done
     * with it.
     *
     * @param owner the owner that takes the log; not null
     * @return the log, or null if no registered instance-local log is available now
     * @throws SagaLogBusyException                       if the log's storage is held by another process; the log
     *                                                    stays available for a later attempt
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already owned in this pool by another owner
     * @throws SagaPoolShutdownException                  if the pool is shut down
     */
    SagaLog tryAcquire(SagaLogOwner owner) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Takes the next available registered instance-local log, with ownership for the given owner, connecting it if
     * needed, waiting up to the given time for one to become available. Same as
     * {@link #tryAcquire(SagaLogOwner, long, TimeUnit)}.
     *
     * @param owner   the owner that takes the log; not null
     * @param timeout the longest time to wait; zero or less does not wait
     * @param unit    the unit of the timeout
     * @return the log, or null if none became available in time
     * @throws InterruptedException                       if the calling thread is interrupted while waiting
     * @throws SagaLogBusyException                       if the log's storage is held by another process; the log
     *                                                    stays available for a later attempt
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already owned in this pool by another owner
     * @throws SagaPoolShutdownException                  if the pool is shut down
     */
    SagaLog tryAcquire(SagaLogOwner owner, int timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Takes the next available registered instance-local log, with ownership for the given owner, connecting it if
     * needed, waiting up to the given time for one to become available. Release it with
     * {@link #releaseOwnership(SagaLogOwner, SagaLogId)} when the saga is done with it.
     * <p>
     * The default implementation, for pools that do not extend {@link AbstractSagaLogPool}, calls
     * {@link #tryAcquire(SagaLogOwner, int, TimeUnit)}, with a timeout above {@code Integer.MAX_VALUE} milliseconds cut
     * to that.
     *
     * @param owner   the owner that takes the log; not null
     * @param timeout the longest time to wait; zero or less does not wait
     * @param unit    the unit of the timeout
     * @return the log, or null if none became available in time
     * @throws InterruptedException                       if the calling thread is interrupted while waiting
     * @throws SagaLogBusyException                       if the log's storage is held by another process; the log
     *                                                    stays available for a later attempt
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already owned in this pool by another owner
     * @throws SagaPoolShutdownException                  if the pool is shut down
     */
    default SagaLog tryAcquire(SagaLogOwner owner, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException {
        long nanos = unit.toNanos(timeout);
        if (nanos <= Integer.MAX_VALUE) {
            return tryAcquire(owner, (int) Math.max(0, nanos), TimeUnit.NANOSECONDS);
        }
        return tryAcquire(owner, (int) Math.min(Integer.MAX_VALUE, unit.toMillis(timeout)), TimeUnit.MILLISECONDS);
    }

    /**
     * Same as {@link #releaseOwnership(SagaLogId)}. A registered instance-local log is available through the tryAcquire
     * methods exactly when nobody owns it, and at most once.
     *
     * @param logId the id of the log to release
     * @throws IllegalStateException if the log is not owned in this pool (never taken, or already released)
     * @deprecated it takes no owner, so any caller can release any owner's log. Use
     * {@link #releaseOwnership(SagaLogOwner, SagaLogId)}, which checks the owner.
     */
    @Deprecated
    void release(SagaLogId logId);

    /**
     * Acquire the saga-log with the given logId, obtaining  exclusive access and ownership to this log across calls to
     * this method in this saga-log pool. If the log is not already present in the pool, this method connects the
     * saga-log first and then add it to the pool.
     *
     * @param owner the owner that wants to acquire the saga-log.
     * @param logId the id of the saga-log to acquire.
     * @return the sagalog with the given logId, or null if another caller in this pool holds it.
     * @throws SagaLogBusyException                       if the saga-log with the given logId was busy, i.e. owned by
     *                                                    another pool or external party.
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already acquired locally in the pool by another owner
     * @throws SagaPoolShutdownException                  if the pool is shut down
     */
    SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Acquire the saga-log with the given logId, obtaining  exclusive access and ownership to this log across calls to
     * this method in this saga-log pool. If the log is not already present in the pool, this method connects the
     * saga-log first and then add it to the pool.
     *
     * @param owner   the owner that wants to acquire the saga-log.
     * @param logId   the id of the saga-log to acquire.
     * @param timeout the longest time to wait for another caller in this pool to release the log
     * @param unit    the unit of the timeout
     * @return the sagalog with the given logId, or null on timeout.
     * @throws InterruptedException                       if the calling thread is interrupted while attempting to obtain ownership of the log
     * @throws SagaLogBusyException                       if the saga-log with the given logId was busy, i.e. owned by another pool or
     *                                                    external party.
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already acquired locally in the pool by another owner
     * @throws SagaPoolShutdownException                  if the pool is shut down
     */
    SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Release ownership of the saga-log with the given logId. A registered instance-local log becomes available through
     * the tryAcquire methods again, however it was taken (tryAcquire or tryTakeOwnership).
     *
     * @param logId the id of the log to release
     * @throws IllegalStateException if the log is not owned in this pool (never taken, or already released)
     * @deprecated it takes no owner, so any caller can release any owner's log. Use
     * {@link #releaseOwnership(SagaLogOwner, SagaLogId)}, which checks the owner.
     */
    @Deprecated
    void releaseOwnership(SagaLogId logId);

    /**
     * Releases the given owner's ownership of the saga-log with the given logId. A registered instance-local log
     * becomes available through the tryAcquire methods again, however it was taken (tryAcquire or tryTakeOwnership).
     * The log stays connected in the pool; use {@link #remove(SagaLogId)} before this to close it.
     * <p>
     * After {@link #shutdown()} the pool holds no ownerships, so this throws {@code IllegalStateException}.
     * <p>
     * The default implementation, for pools that do not extend {@link AbstractSagaLogPool}, throws
     * {@code UnsupportedOperationException}.
     *
     * @param owner the owner that holds the log; not null
     * @param logId the id of the log to release
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if another owner holds the log; nothing is released
     * @throws IllegalStateException                      if the log is not owned in this pool (never taken, already
     *                                                    released, or the pool is shut down)
     */
    default void releaseOwnership(SagaLogOwner owner, SagaLogId logId) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement releaseOwnership(SagaLogOwner, SagaLogId)");
    }

    /**
     * Deletes the log's storage (for example the file, or the table and its lock row). The log must be removed from
     * the pool first.
     *
     * @param logId the id of the log to delete
     * @return true if the underlying saga-log associated with logId was deleted, false otherwise.
     * @throws IllegalStateException if the log is still in the pool
     */
    boolean delete(SagaLogId logId);

    /**
     * Shuts the pool down: closes and removes every log in the pool, and clears its ownerships. Every log is closed,
     * also when closing one fails; the first failure is thrown, with later ones added as suppressed. After shutdown,
     * {@code connect}, {@code tryAcquire} and {@code tryTakeOwnership} throw {@link SagaPoolShutdownException}. Shutting
     * down a pool that is already shut down does nothing more.
     */
    void shutdown();
}
