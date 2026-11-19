package no.cantara.concurrent.futureselector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link CompletableFuture} that is also the task that completes it, so that it can be run by an executor and
 * selected by a {@link FutureSelector}. Like {@link java.util.concurrent.FutureTask}, it runs its task at most once:
 * a second call to {@link #run()}, from any thread, does nothing.
 *
 * @param <V> the result type
 */
public class SelectableFuture<V> extends CompletableFuture<V> implements RunnableFuture<V> {

    private final Callable<V> wrap;
    private final AtomicBoolean started = new AtomicBoolean();

    public SelectableFuture(Runnable runnable, V value) {
        wrap = () -> {
            runnable.run();
            return value;
        };
    }

    public SelectableFuture(Callable<V> wrap) {
        this.wrap = wrap;
    }

    /**
     * Runs the task and completes this future with its result, or exceptionally with what it threw. Runs the task
     * only on the first call; later calls return at once.
     */
    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            complete(wrap.call());
        } catch (Throwable t) {
            completeExceptionally(t);
        }
    }

    <C> SelectableFuture<V> registerWithDoneQueueAndMarkSelectableIfDone(BlockingQueue<? super Selection<V, C>> doneQueue, Selection<V, C> selection) {
        // whenComplete, not thenAccept: a future that completes exceptionally must be selectable too, or a caller
        // waiting in select() for it waits for ever.
        // The done-queue is unbounded, so add never fails.
        whenComplete((v, t) -> doneQueue.add(selection));
        return this;
    }
}
