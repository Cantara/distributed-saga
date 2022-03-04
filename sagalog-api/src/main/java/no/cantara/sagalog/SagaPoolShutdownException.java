package no.cantara.sagalog;

public class SagaPoolShutdownException extends RuntimeException {
    public SagaPoolShutdownException() {
        super();
    }

    public SagaPoolShutdownException(String message) {
        super(message);
    }
}
