package no.cantara.saga.execution;

/**
 * The saga completed successfully, but the {@code onComplete} consumer given to
 * {@link SagaExecution#executeSaga(String, Object, boolean, java.util.function.Consumer)} threw. The cause is the
 * consumer's exception. The saga's End entry is in the saga log.
 */
public class SagaOnCompleteException extends RuntimeException {

    private final String executionId;

    public SagaOnCompleteException(String executionId, Throwable cause) {
        super(String.format("onComplete failed after saga execution %s completed successfully", executionId), cause);
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
