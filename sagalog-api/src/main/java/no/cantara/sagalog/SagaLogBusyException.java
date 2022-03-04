package no.cantara.sagalog;

public class SagaLogBusyException extends RuntimeException {
    public SagaLogBusyException() {
    }

    public SagaLogBusyException(String message) {
        super(message);
    }

    public SagaLogBusyException(String message, Throwable cause) {
        super(message, cause);
    }

    public SagaLogBusyException(Throwable cause) {
        super(cause);
    }

    public SagaLogBusyException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
