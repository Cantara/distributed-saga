package no.cantara.saga.api;

public class SagaException extends RuntimeException {
    public SagaException(String message) {
        super(message);
    }
}
