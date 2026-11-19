package no.cantara.saga.execution;

/**
 * The outcome of a saga execution, given to {@code onComplete} and by the handoff and completion futures.
 */
public class SagaHandoffResult {
    private final String executionId;
    private final boolean failure;
    private final Throwable failureCause;

    SagaHandoffResult(String executionId) {
        this.executionId = executionId;
        this.failure = false;
        this.failureCause = null;
    }

    SagaHandoffResult(String executionId, Throwable failureCause) {
        this.executionId = executionId;
        this.failure = true;
        this.failureCause = failureCause;
    }

    /**
     * @return the id of the execution
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * @return true if the saga succeeded
     */
    public boolean isSuccess() {
        return !failure;
    }

    /**
     * @return true if the saga failed: rolled back after an abort, or failed with another exception
     */
    public boolean isFailure() {
        return failure;
    }

    /**
     * @return the saga's failure ({@code AbortSagaException} after a rollback), or null on success
     */
    public Throwable getFailureCause() {
        return failureCause;
    }
}
