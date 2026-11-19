package no.cantara.concurrent.futureselector;

public class TimeoutRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TimeoutRuntimeException() {
        super();
    }

    public TimeoutRuntimeException(String message) {
        super(message);
    }

    public TimeoutRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public TimeoutRuntimeException(Throwable cause) {
        super(cause);
    }
}
