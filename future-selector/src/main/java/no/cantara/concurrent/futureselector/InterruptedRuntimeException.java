package no.cantara.concurrent.futureselector;

public class InterruptedRuntimeException extends RuntimeException {
    public InterruptedRuntimeException() {
        super();
    }

    public InterruptedRuntimeException(String message) {
        super(message);
    }

    public InterruptedRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public InterruptedRuntimeException(Throwable cause) {
        super(cause);
    }
}
