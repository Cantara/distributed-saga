package no.cantara.sagalog;

public class SagaLogAlreadyAquiredByOtherOwnerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SagaLogAlreadyAquiredByOtherOwnerException() {
        super();
    }

    public SagaLogAlreadyAquiredByOtherOwnerException(String message) {
        super(message);
    }
}
