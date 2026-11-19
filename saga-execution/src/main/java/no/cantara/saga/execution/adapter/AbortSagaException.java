package no.cantara.saga.execution.adapter;

/**
 * Thrown by an action to roll the saga back. Throw a new instance each time: the engine attaches other failures of
 * the same saga to it as suppressed exceptions.
 */
public class AbortSagaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

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
