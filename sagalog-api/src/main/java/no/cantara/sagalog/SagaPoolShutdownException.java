package no.cantara.sagalog;

/**
 * Thrown by {@link SagaLogPool#connect}, the {@code tryAcquire} methods and the {@code tryTakeOwnership} methods of a
 * pool that is shut down, and by a connect that was in progress when the pool was shut down.
 */
public class SagaPoolShutdownException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SagaPoolShutdownException() {
        super();
    }

    public SagaPoolShutdownException(String message) {
        super(message);
    }
}
