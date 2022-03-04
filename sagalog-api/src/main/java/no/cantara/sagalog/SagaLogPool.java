package no.cantara.sagalog;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface SagaLogPool {

    /**
     * Get the cluster-instance-id used when registering and managing instance-local saga-logs or saga-log-ids.
     *
     * @return the instance-local cluster-id.
     */
    String getLocalClusterInstanceId();

    /**
     * Generate a SagaLogId for the given clusterInstanceId and internalId.
     *
     * @param clusterInstanceId
     * @param logName
     * @return
     */
    SagaLogId idFor(String clusterInstanceId, String logName);

    /**
     * Generate and register an instance-local SagaLogId for the given logName. The returned values are considered
     * instance-local ids and will be included by the instanceLocalLogIds method.
     *
     * @param logName
     * @return
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
     * List all logIds in the cluster owned by this instance that is relevant to a recovery procedure. This will include
     * all saga-logs owned by this instance that might have incomplete entries. These entries are typically not removed
     * from this pool after being used in a recovery or administrative procedure.
     *
     * @return
     */
    Set<SagaLogId> instanceLocalLogIds();

    /**
     * List all saga-log ownerships currently registered with the pool. This is an unreliable snapshot of ownerships
     * typically only used for monitoring or health purposes.
     *
     * @return
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
     * @throws SagaLogBusyException if the saga-log is busy due to this pool not being able to take exclusive ownership
     *                              of the related external resource.
     */
    SagaLog connect(SagaLogId logId) throws SagaLogBusyException;

    /**
     * Close and then remove (but does not release) the saga-log with the given logId from the pool. This will cause
     * subsequent calls to acquire on the same logId to re-connect to the log. Note that if the log is not already
     * released when this method is called, then this method will throw an IllegalStateException.
     *
     * @param logId
     */
    void remove(SagaLogId logId);

    /**
     * @param owner
     * @return
     * @throws SagaLogBusyException
     * @throws SagaLogAlreadyAquiredByOtherOwnerException
     */
    SagaLog tryAcquire(SagaLogOwner owner) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * @param owner
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws SagaLogBusyException
     * @throws SagaLogAlreadyAquiredByOtherOwnerException
     */
    SagaLog tryAcquire(SagaLogOwner owner, int timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Releases ownership, then returns the saga-log with the given logId to the pool making it available through the
     * tryAcquire methods again.
     *
     * @param logId
     */
    void release(SagaLogId logId);

    /**
     * Acquire the saga-log with the given logId, obtaining  exclusive access and ownership to this log across calls to
     * this method in this saga-log pool. If the log is not already present in the pool, this method connects the
     * saga-log first and then add it to the pool.
     *
     * @param owner the owner that wants to acquire the saga-log.
     * @param logId the id of the saga-log to acquire.
     * @return the sagalog with the given logI.
     * @throws SagaLogBusyException                       if the saga-log with the given logId was already acquired without yet
     *                                                    being released.
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already acquired locally in the pool by another owner
     */
    SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId) throws SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Acquire the saga-log with the given logId, obtaining  exclusive access and ownership to this log across calls to
     * this method in this saga-log pool. If the log is not already present in the pool, this method connects the
     * saga-log first and then add it to the pool.
     *
     * @param owner the owner that wants to acquire the saga-log.
     * @param logId the id of the saga-log to acquire.
     * @return the sagalog with the given logId.
     * @throws InterruptedException                       if the calling thread is interrupted while attempting to obtain ownership of the log
     * @throws SagaLogBusyException                       if the saga-log with the given logId was busy, i.e. owned by another pool or
     *                                                    external party.
     * @throws SagaLogAlreadyAquiredByOtherOwnerException if the log is already acquired locally in the pool by another owner
     */
    SagaLog tryTakeOwnership(SagaLogOwner owner, SagaLogId logId, long timeout, TimeUnit unit) throws InterruptedException, SagaLogBusyException, SagaLogAlreadyAquiredByOtherOwnerException;

    /**
     * Release ownership of the saga-log with the given logId.
     *
     * @param logId
     */
    void releaseOwnership(SagaLogId logId);

    /**
     * @param logId
     * @return true if the underlying saga-log associated with logId was deleted, false otherwise.
     */
    boolean delete(SagaLogId logId);

    /**
     * Shutdown the pool closing and removing all logs from the pool. Shutting down an alread
     */
    void shutdown();
}
