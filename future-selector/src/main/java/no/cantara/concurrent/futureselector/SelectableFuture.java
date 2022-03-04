package no.cantara.concurrent.futureselector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RunnableFuture;

public class SelectableFuture<V> extends CompletableFuture<V> implements RunnableFuture<V> {

    private final Callable<V> wrap;

    public SelectableFuture(Runnable runnable, V value) {
        wrap = () -> {
            runnable.run();
            return value;
        };
    }

    public SelectableFuture(Callable<V> wrap) {
        this.wrap = wrap;
    }

    @Override
    public void run() {
        try {
            complete(wrap.call());
        } catch (Throwable t) {
            completeExceptionally(t);
        }
    }

    SelectableFuture<V> registerWithDoneQueueAndMarkSelectableIfDone(BlockingQueue<Selection> doneQueue, Selection selection) {
        thenAccept(v -> {
            if (!doneQueue.offer(selection)) {
                throw new IllegalStateException("Unable to offer this Future instance to doneQueue.");
            }
        });
        return this;
    }
}
