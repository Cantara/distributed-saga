package no.cantara.concurrent.futureselector;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * This is a modified version based on work authored by Brian Goetz and Tim Peierls
 */
public class Utils {

    /**
     * Coerce an unchecked Throwable to a RuntimeException
     * <p/>
     * If the Throwable is an Error, throw it; if it is a
     * RuntimeException return it, otherwise throw IllegalStateException
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
            return new InterruptedRuntimeException(t);
        } else {
            throw new IllegalStateException("Not unchecked", t);
        }
    }
}