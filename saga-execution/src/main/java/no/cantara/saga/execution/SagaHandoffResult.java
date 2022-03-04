package no.cantara.saga.execution;

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

    public String getExecutionId() {
        return executionId;
    }

    public boolean isSuccess() {
        return !failure;
    }

    public boolean isFailure() {
        return failure;
    }

    public Throwable getFailureCause() {
        return failureCause;
    }
}
