package no.cantara.concurrent.futureselector;

/**
 * An {@link InterruptedException} laundered by {@link Utils#launder(Throwable)}. The thread that threw it is still
 * interrupted: its interrupt status was restored when the exception was laundered.
 */
public class InterruptedRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

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
