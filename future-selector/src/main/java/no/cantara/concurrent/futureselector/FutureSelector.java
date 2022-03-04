package no.cantara.concurrent.futureselector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static no.cantara.concurrent.futureselector.Utils.launder;

public class FutureSelector<F, C> {

    private final AtomicInteger taskCount = new AtomicInteger(0);
    private final BlockingQueue<Selection> doneQueue = new LinkedBlockingQueue<>();

    /**
     * Selects the next future that is done, waiting if necessary for a future to be done.
     *
     * @return the selected future and control.
     */
    public Selection<F, C> select() {
        if (taskCount.get() <= 0) {
            throw new IllegalStateException("Attempting to select future for tasks that were never created with \"newFuture\"");
        }
        Selection<F, C> selected;
        try {
            selected = doneQueue.take(); // safe unchecked assignment
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
     */
    public Selection<F, C> select(long timeout, TimeUnit unit) {
        if (taskCount.get() <= 0) {
            throw new IllegalStateException("Attempting to select future for tasks that were never created with \"newFuture\"");
        }
        Selection<F, C> selected;
        try {
            selected = doneQueue.poll(timeout, unit); // safe unchecked assignment
            if (selected == null) {
            }
        } catch (InterruptedException e) {
            throw launder(e);
        }
        taskCount.decrementAndGet(); // always decrement taskCount only after taking the task off the queue.
        return selected;
    }

    /**
     * @return true if at least one future that was previously added has not yet been selected, false otherwise.
     * false otherwise.
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
     * Registers a selectable-future with this selector.
     *
     * @param selectableFuture
     * @param control
     * @return (taskcount + 1) as it was immediately before registering the given selectableFuture with this selector.
     */
    public int add(SelectableFuture<F> selectableFuture, C control) {
        // taskCount must be incremented before adding task to queue in order to avoid race-condition with pending methods.
        int countAfter = taskCount.incrementAndGet();

        selectableFuture.registerWithDoneQueueAndMarkSelectableIfDone(doneQueue, new Selection<>(selectableFuture, control));
        return countAfter;
    }
}
