package no.cantara.saga.execution.adapter;

public class AbortSagaException extends RuntimeException {
    public AbortSagaException() {
    }

    public AbortSagaException(String message) {
        super(message);
    }

    public AbortSagaException(String message, Throwable cause) {
        super(message, cause);
    }

    public AbortSagaException(Throwable cause) {
        super(cause);
    }

    public AbortSagaException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
