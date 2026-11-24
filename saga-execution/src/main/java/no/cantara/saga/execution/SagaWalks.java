package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The walks of one saga execution, forward and backward: counts the walks that may still run an action or write to
 * the saga log, and completes a failed saga only once none is left (se-1, se-2).
 * <p>
 * A walk is counted from just before it is submitted (or run) until its entry point has returned, or until it is
 * known never to run (rejected, cancelled, or claimed after its traversal stopped). When the count reaches zero:
 * <ul>
 * <li>if a failure was recorded, the handoff and completion futures complete exceptionally with the first failure;</li>
 * <li>otherwise, the task registered with {@link #whenIdle(Runnable)} runs once (the rollback after an abort), on
 * the thread that ended the last walk, counted as a walk itself, with a catch-all entry point (se-3).</li>
 * </ul>
 * A successful saga, and a completed rollback, are completed by the visit of the last node ({@code E} forward,
 * {@code S} backward), which is the join of every other walk.
 */
final class SagaWalks {

    private final SelectableFuture<SagaHandoffResult> handoffFuture;
    private final SelectableFuture<SagaHandoffResult> completionFuture;
    private final AtomicInteger live = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile Runnable whenIdle;

    /**
     * @param handoffFuture    failed at once by the first failure; may be null
     * @param completionFuture failed by the first failure once no walk is left; may be null
     */
    SagaWalks(SelectableFuture<SagaHandoffResult> handoffFuture, SelectableFuture<SagaHandoffResult> completionFuture) {
        this.handoffFuture = handoffFuture;
        this.completionFuture = completionFuture;
    }

    /**
     * Counts a walk that is about to be submitted or run.
     */
    void started() {
        live.incrementAndGet();
    }

    /**
     * Ends a counted walk; the last one completes a failed saga or starts the task registered to run when idle.
     */
    void ended() {
        if (live.decrementAndGet() == 0) {
            idle();
        }
    }

    /**
     * Records a failure of the saga. The first failure is the saga's failure; a later, different one is added to it
     * as suppressed. The handoff future fails at once (it says nothing about the saga having ended); the completion
     * future fails when no walk is left.
     */
    void fail(Throwable t) {
        if (failure.compareAndSet(null, t)) {
            if (handoffFuture != null) {
                handoffFuture.completeExceptionally(t);
            }
            return;
        }
        Throwable first = failure.get();
        if (first != t && !causedBy(t, first) && !isSuppressedOn(first, t)) {
            first.addSuppressed(t);
        }
    }

    boolean failed() {
        return failure.get() != null;
    }

    /**
     * @return the first failure recorded, or null
     */
    Throwable failure() {
        return failure.get();
    }

    /**
     * @return the walks counted now
     */
    int live() {
        return live.get();
    }

    /**
     * Registers the task to run once no walk is left and no failure was recorded. Called by a counted walk, so the
     * count cannot reach zero before the task is registered.
     */
    void whenIdle(Runnable task) {
        this.whenIdle = task;
    }

    private void idle() {
        Runnable task = whenIdle;
        if (task != null && failure.get() == null) {
            whenIdle = null;
            started(); // the task counts as a walk while it runs, so the walks it starts cannot end the saga early
            try {
                task.run();
            } catch (Throwable t) {
                fail(t);
            } finally {
                ended();
            }
            return;
        }
        Throwable first = failure.get();
        if (first != null) {
            if (handoffFuture != null) {
                handoffFuture.completeExceptionally(first);
            }
            if (completionFuture != null) {
                completionFuture.completeExceptionally(first);
            }
        }
    }

    private static boolean causedBy(Throwable t, Throwable cause) {
        int depth = 0;
        for (Throwable c = t.getCause(); c != null && depth < 32; c = c.getCause(), depth++) {
            if (c == cause) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuppressedOn(Throwable on, Throwable t) {
        for (Throwable s : on.getSuppressed()) {
            if (s == t) {
                return true;
            }
        }
        return false;
    }
}
