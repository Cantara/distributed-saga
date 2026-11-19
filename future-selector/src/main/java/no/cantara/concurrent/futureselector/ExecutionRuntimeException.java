package no.cantara.concurrent.futureselector;

public class ExecutionRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ExecutionRuntimeException() {
        super();
    }

    public ExecutionRuntimeException(String message) {
        super(message);
    }

    public ExecutionRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExecutionRuntimeException(Throwable cause) {
        super(cause);
    }
}
