package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.ExecutionRuntimeException;
import no.cantara.concurrent.futureselector.FutureSelector;
import no.cantara.concurrent.futureselector.InterruptedRuntimeException;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.Selection;
import no.cantara.concurrent.futureselector.TimeoutRuntimeException;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static no.cantara.concurrent.futureselector.Utils.launder;

public class SagaTraversalResult {
    final Saga saga;
    final AtomicInteger pendingWalks;
    final BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk;
    final ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> visitFutureById;

    private final Object sync = new Object(); // protects completedThreadWalks
    private Collection<SelectableFuture<List<String>>> completedThreadWalks;

    SagaTraversalResult(Saga saga, AtomicInteger pendingWalks, BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk, ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> visitFutureById) {
        this.saga = saga;
        this.pendingWalks = pendingWalks;
        this.futureThreadWalk = futureThreadWalk;
        this.visitFutureById = visitFutureById;
    }

    public void waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(long timeout, TimeUnit unit) {
        waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(timeout, unit, futureThreadWalks -> {
        }, (node, output) -> {
        });
    }

    public void waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(long timeout, TimeUnit unit, Consumer<SelectableFuture<List<String>>> threadWalkConsumer, BiConsumer<SagaNode, Object> visitConsumer) {
        long startTime = System.currentTimeMillis();
        long durationMs = unit.toMillis(timeout);

        Collection<SelectableFuture<List<String>>> futureWalks =
                completeAllThreadWalks(startTime, durationMs);

        for (SelectableFuture<List<String>> futureWalk : futureWalks) {
            threadWalkConsumer.accept(futureWalk);
        }

        FutureSelector<Object, SagaNode> visitSelector = getVisitFutureSelector();

        while (visitSelector.pending()) {
            Selection<Object, SagaNode> selected = visitSelector.select();
            Object output;
            try {
                output = selected.future.get(); // will never block
            } catch (Exception e) {
                throw launder(e);
            }
            visitConsumer.accept(selected.control, output);
        }
    }

    /**
     * Check the future results of all saga graph walks. These future results are created one
     * per parallel execution. Each parallel execution may walk one or more nodes.
     * <p>
     * After this method has completed normally, it is guaranteed that all nodes were
     * walked, and that their execution was successful.
     *
     * @param startTime  start time as a reference for timeout
     * @param durationMs total number of milliseconds that this operation is allowed to take
     *                   before a TimeoutException should be thrown.
     * @return a collection of all the thread-walks performed during saga-traversal
     * @throws TimeoutRuntimeException     if the time is up before all walk results could be retrieved.
     * @throws InterruptedRuntimeException if the calling thread is interrupted while waiting on walk
     *                                     results to complete.
     * @throws ExecutionRuntimeException   if there were any exceptions thrown from a visit.
     */
    private Collection<SelectableFuture<List<String>>> completeAllThreadWalks(long startTime, long durationMs) {
        Collection<SelectableFuture<List<String>>> completedThreadWalks = waitForThreadWalksToComplete(startTime, durationMs);

        Set<String> nodeIdsWalked = new LinkedHashSet<>();
        for (SelectableFuture<List<String>> threadWalk : completedThreadWalks) {
            try {
                nodeIdsWalked.addAll(threadWalk.get()); // will never block
            } catch (InterruptedException | ExecutionException e) {
                launder(e);
            }
        }

        // select walks as they become complete, although they should generally all be complete by this point.
        Set<String> remainingNodeIdsToThreadWalk = new LinkedHashSet<>(saga.nodes().stream().map(s -> s.id).collect(Collectors.toSet()));
        remainingNodeIdsToThreadWalk.removeAll(nodeIdsWalked);

        // check that all nodes in saga were walked
        if (!remainingNodeIdsToThreadWalk.isEmpty()) {
            throw new IllegalStateException("Traversal failed. The following nodes were not walked: " + remainingNodeIdsToThreadWalk);
        }

        return completedThreadWalks;
    }

    Collection<SelectableFuture<List<String>>> waitForThreadWalksToComplete() {
        return waitForThreadWalksToComplete(-1, -1);
    }

    private Collection<SelectableFuture<List<String>>> waitForThreadWalksToComplete(long startTime, long durationMs) {
        synchronized (sync) {
            if (completedThreadWalks != null) {
                return completedThreadWalks;
            }

            Collection<SelectableFuture<List<String>>> result = new ArrayList<>();

            FutureSelector<List<String>, Object> walkSelector = new FutureSelector<>();
            result.addAll(drainAllAvailableWalkFuturesAndAddtoSelector(walkSelector));
            if (result.isEmpty()) {
                throw new IllegalStateException("No traversal walk futures detected, could this be a bug in the algorithm?");
            }

            // register all walk futures with a new selector.
            for (SelectableFuture<List<String>> futureWalk : result) {
                walkSelector.add(futureWalk, null);
            }
            while (walkSelector.pending()) {
                Selection<List<String>, Object> selected = walkSelector.select();
                try {
                    if (startTime == -1) {
                        selected.future.get();
                    } else {
                        long remainingMs = remaining(startTime, durationMs);
                        selected.future.get(remainingMs, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    throw launder(e);
                }
                // potentially more walks available now that we have waited on the selected walk to complete
                result.addAll(drainAllAvailableWalkFuturesAndAddtoSelector(walkSelector));
            }

            completedThreadWalks = result;
            return result;
        }
    }

    private ArrayList<SelectableFuture<List<String>>> drainAllAvailableWalkFuturesAndAddtoSelector(FutureSelector<List<String>, Object> selector) {
        ArrayList<SelectableFuture<List<String>>> futureWalks = new ArrayList<>();
        pendingWalks.addAndGet(-futureThreadWalk.drainTo(futureWalks));
        for (SelectableFuture<List<String>> futureWalk : futureWalks) {
            selector.add(futureWalk, null);
        }
        return futureWalks;
    }

    private FutureSelector<Object, SagaNode> getVisitFutureSelector() {
        FutureSelector<Object, SagaNode> visitSelector = new FutureSelector<>();
        for (SagaNode node : saga.nodes()) {
            Future<SelectableFuture<Object>> futureFuture = visitFutureById.get(node.id);
            SelectableFuture<Object> selectableFuture;
            try {
                if (futureFuture == null) {
                    System.out.println("Not visited node: " + node.id);
                } else {
                    selectableFuture = futureFuture.get(); // will not block
                    visitSelector.add(selectableFuture, node);
                }
            } catch (Exception e) {
                throw launder(e);
            }
            //visitSelector.add(selectableFuture, node);
        }
        return visitSelector;
    }

    private long remaining(long startTime, long durationMs) {
        long remainingMs = durationMs - (System.currentTimeMillis() - startTime);
        if (remainingMs <= 0) {
            throw new TimeoutRuntimeException();
        }
        return remainingMs;
    }
}
