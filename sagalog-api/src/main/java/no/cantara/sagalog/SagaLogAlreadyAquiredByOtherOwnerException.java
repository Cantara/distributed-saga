package no.cantara.sagalog;

public class SagaLogAlreadyAquiredByOtherOwnerException extends RuntimeException {
    public SagaLogAlreadyAquiredByOtherOwnerException() {
        super();
    }

    public SagaLogAlreadyAquiredByOtherOwnerException(String message) {
        super(message);
    }
}
