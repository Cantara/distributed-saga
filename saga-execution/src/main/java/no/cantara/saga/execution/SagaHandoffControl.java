package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;

/**
 * Returned by {@link SagaExecution#executeSaga}: the execution's handoff and completion futures, and its inputs.
 * <p>
 * The futures are the ones the engine completes. Completing, cancelling or obtruding them does not stop or change the
 * saga, and changes what every other caller waiting on them sees (a cancelled completion future no longer reports the
 * saga's outcome). Wait on them, or on a copy ({@code future.copy()}), and leave them to the engine.
 */
public class SagaHandoffControl {
    private final Object sagaInput;
    private final String executionId;
    private final Saga saga;
    private final SagaLog sagaLog;
    private final AdapterLoader adapterLoader;
    private final SagaTraversalResult traversalResult;
    private final SelectableFuture<SagaHandoffResult> handoffFuture;
    private final SelectableFuture<SagaHandoffResult> completionFuture;

    SagaHandoffControl(Object sagaInput, String executionId, Saga saga, SagaLog sagaLog, AdapterLoader adapterLoader, SagaTraversalResult traversalResult, SelectableFuture<SagaHandoffResult> handoffFuture, SelectableFuture<SagaHandoffResult> completionFuture) {
        this.sagaInput = sagaInput;
        this.executionId = executionId;
        this.saga = saga;
        this.sagaLog = sagaLog;
        this.adapterLoader = adapterLoader;
        this.traversalResult = traversalResult;
        this.handoffFuture = handoffFuture;
        this.completionFuture = completionFuture;
    }

    /**
     * @return the input given to the execution
     */
    public Object getSagaInput() {
        return sagaInput;
    }

    /**
     * @return the id of the execution
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * @return the saga
     */
    public Saga getSaga() {
        return saga;
    }

    /**
     * @return the saga log the execution writes to
     */
    public SagaLog getSagaLog() {
        return sagaLog;
    }

    /**
     * @return the adapters of the execution
     */
    public AdapterLoader getAdapterLoader() {
        return adapterLoader;
    }

    /**
     * @return the forward traversal's walks, for tests; see {@link SagaTraversalResult}
     */
    public SagaTraversalResult getTraversalResult() {
        return traversalResult;
    }

    /**
     * @return a future that completes when the {@code Start} entry on {@code S} is written (in recovery, when the
     * first walk starts), or fails at once with the saga's first failure if that comes before
     */
    public SelectableFuture<SagaHandoffResult> getHandoffFuture() {
        return handoffFuture;
    }

    /**
     * @return a future that completes when the saga has completed and {@code onComplete} has returned; see
     * {@link SagaExecution#executeSaga(String, Object, boolean, java.util.function.Consumer, java.util.function.Consumer, java.util.function.Consumer)}
     */
    public SelectableFuture<SagaHandoffResult> getCompletionFuture() {
        return completionFuture;
    }
}
