package no.cantara.concurrent.futureselector;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Turns the checked exceptions of {@code java.util.concurrent} into this module's unchecked ones, at the boundary
 * where they are caught. This is the exception style of the whole library.
 * <p>
 * This is a modified version based on work authored by Brian Goetz and Tim Peierls
 */
public class Utils {

    private Utils() {
    }

    /**
     * Coerce a Throwable to a RuntimeException.
     * <ul>
     * <li>an {@link Error} is thrown;</li>
     * <li>a {@link RuntimeException} is returned as it is;</li>
     * <li>an {@link ExecutionException}, {@link TimeoutException} or {@link InterruptedException} is returned wrapped
     * in an {@link ExecutionRuntimeException}, {@link TimeoutRuntimeException} or {@link InterruptedRuntimeException}.
     * For an {@code InterruptedException} the calling thread's interrupt status is restored first, because catching
     * the exception cleared it: code further up that catches the runtime exception still sees that the thread was
     * interrupted;</li>
     * <li>for any other checked exception this method throws {@link IllegalStateException}.</li>
     * </ul>
     *
     * @param t the throwable to launder
     * @return the unchecked exception, for the caller to throw
     */
    public static RuntimeException launder(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        } else if (t instanceof Error) {
            throw (Error) t;
        } else if (t instanceof ExecutionException) {
            return new ExecutionRuntimeException(t);
        } else if (t instanceof TimeoutException) {
            return new TimeoutRuntimeException(t);
        } else if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new InterruptedRuntimeException(t);
        } else {
            throw new IllegalStateException("Not unchecked", t);
        }
    }
}
