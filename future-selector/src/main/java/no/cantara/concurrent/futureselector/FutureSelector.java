package no.cantara.concurrent.futureselector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static no.cantara.concurrent.futureselector.Utils.launder;

/**
 * Hands out added futures in the order they complete, normally or exceptionally.
 * <p>
 * Threading contract:
 * <ul>
 * <li>any thread may {@link #add(SelectableFuture, Object) add} a future, at any time;</li>
 * <li>only <b>one</b> thread selects: {@link #select()} and {@link #select(long, TimeUnit)} must not be called by
 * two threads at the same time. Two threads that both see one pending future would both wait for it; one gets it,
 * and the other waits for ever;</li>
 * <li>{@link #pending()} and {@link #moreThanOnePending()} are exact only on the selecting thread. On other threads
 * they are a snapshot.</li>
 * </ul>
 * A select that is interrupted throws {@link InterruptedRuntimeException} and leaves the thread interrupted.
 *
 * @param <F> the result type of the futures
 * @param <C> the type of the control value added with each future
 */
public class FutureSelector<F, C> {

    private final AtomicInteger taskCount = new AtomicInteger(0);
    private final BlockingQueue<Selection<F, C>> doneQueue = new LinkedBlockingQueue<>();

    public FutureSelector() {
    }

    /**
     * Selects the next future that is done, waiting if necessary for a future to be done.
     *
     * @return the selected future and control.
     * @throws IllegalStateException        if no future is pending
     * @throws InterruptedRuntimeException if the thread is interrupted while waiting; it stays interrupted
     */
    public Selection<F, C> select() {
        if (taskCount.get() <= 0) {
            throw new IllegalStateException("Attempting to select future for tasks that were never created with \"newFuture\"");
        }
        Selection<F, C> selected;
        try {
            selected = doneQueue.take();
        } catch (InterruptedException e) {
            throw launder(e);
        }
        taskCount.decrementAndGet(); // always decrement taskCount only after taking the task off the queue.
        return selected;
    }

    /**
     * Selects the next future that is done, waiting up to the
     * specified wait time if necessary for a future to be done.
     *
     * @param timeout how long to wait before giving up, in units of
     *                {@code unit}
     * @param unit    a {@code TimeUnit} determining how to interpret the
     *                {@code timeout} parameter
     * @return the selected future and control, or null if the specified waiting time elapses before a future was done.
     * @throws IllegalStateException        if no future is pending
     * @throws InterruptedRuntimeException if the thread is interrupted while waiting; it stays interrupted
     */
    public Selection<F, C> select(long timeout, TimeUnit unit) {
        if (taskCount.get() <= 0) {
            throw new IllegalStateException("Attempting to select future for tasks that were never created with \"newFuture\"");
        }
        Selection<F, C> selected;
        try {
            selected = doneQueue.poll(timeout, unit);
        } catch (InterruptedException e) {
            throw launder(e);
        }
        if (selected != null) {
            taskCount.decrementAndGet(); // only a future taken off the queue is selected; a timeout selects nothing
        }
        return selected;
    }

    /**
     * @return true if at least one future that was previously added has not yet been selected, false otherwise.
     */
    public boolean pending() {
        return taskCount.get() > 0;
    }

    /**
     * @return true if more than one future that was previously added has not yet been selected, false otherwise.
     */
    public boolean moreThanOnePending() {
        return taskCount.get() > 1;
    }

    /**
     * Registers a selectable-future with this selector. Safe to call from any thread.
     *
     * @param selectableFuture the future to select when it is done
     * @param control          the control value to hand out with it
     * @return (taskcount + 1) as it was immediately before registering the given selectableFuture with this selector.
     */
    public int add(SelectableFuture<F> selectableFuture, C control) {
        // taskCount must be incremented before adding task to queue in order to avoid race-condition with pending methods.
        int countAfter = taskCount.incrementAndGet();

        selectableFuture.registerWithDoneQueueAndMarkSelectableIfDone(doneQueue, new Selection<>(selectableFuture, control));
        return countAfter;
    }
}
