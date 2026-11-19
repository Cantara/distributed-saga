package no.cantara.saga.api;

public class SagaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SagaException(String message) {
        super(message);
    }

    public SagaException(String message, Throwable cause) {
        super(message, cause);
    }
}
