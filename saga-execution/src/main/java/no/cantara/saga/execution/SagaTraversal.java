package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.FutureSelector;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.concurrent.futureselector.Selection;
import no.cantara.concurrent.futureselector.Utils;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

class SagaTraversal {

    private final SelectableThreadPoolExectutor executorService;
    private final Saga saga;
    private final AtomicBoolean stopSignal = new AtomicBoolean(false);
    private final SelectableFuture<Object> stopSelectable = new SelectableFuture<>(() -> null);
    private final SelectableFuture<SelectableFuture<Object>> stopSelectableSelectable = new SelectableFuture<>(() -> null);

    SagaTraversal(SelectableThreadPoolExectutor executorService, Saga saga) {
        this.executorService = executorService;
        this.saga = saga;
    }

    /**
     * @return true iff the calling thread was the first to stop the traversal.
     */
    boolean stopTraversal() {
        boolean firstToStop = stopSignal.compareAndSet(false, true);
        if (firstToStop) {
            stopSelectable.complete(null);
            stopSelectableSelectable.complete(null);
        }
        return firstToStop;
    }

    SagaTraversalResult forward(Function<SagaTraversalElement, Object> visit) {
        return forward(null, null, visit);
    }

    SagaTraversalResult forward(
            SelectableFuture<SagaHandoffResult> handoffFuture,
            SelectableFuture<SagaHandoffResult> completionFuture,
            Function<SagaTraversalElement, Object> visit) {
        SagaNode startNode = saga.getStartNode();
        return traverse(true, startNode, handoffFuture, completionFuture, visit, new AtomicInteger(1), new LinkedBlockingQueue<>(), new ConcurrentHashMap<>());
    }

    SagaTraversalResult backward(Function<SagaTraversalElement, Object> visit) {
        return backward(null, null, new AtomicInteger(1), new LinkedBlockingQueue<>(), new ConcurrentHashMap<>(), visit);
    }

    SagaTraversalResult backward(
            SelectableFuture<SagaHandoffResult> handoffFuture,
            SelectableFuture<SagaHandoffResult> completionFuture,
            AtomicInteger pendingWalks,
            BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk,
            ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> futureById,
            Function<SagaTraversalElement, Object> visit) {
        SagaNode endNode = saga.getEndNode();
        return traverse(false, endNode, handoffFuture, completionFuture, visit, pendingWalks, futureThreadWalk, futureById);
    }

    private SagaTraversalResult traverse(
            boolean forward,
            SagaNode firstNode,
            SelectableFuture<SagaHandoffResult> handoffFuture,
            SelectableFuture<SagaHandoffResult> completionFuture,
            Function<SagaTraversalElement, Object> visit,
            AtomicInteger pendingWalks,
            BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk,
            ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> futureById) {

        ConcurrentHashMap<String, SagaNode> visitedById = new ConcurrentHashMap<>();
        visitedById.putIfAbsent(firstNode.id, firstNode);
        SelectableFuture<List<String>> future = executorService.submit(() -> {
                    try {
                        return traverse(
                                pendingWalks,
                                futureThreadWalk,
                                new ArrayList<>(),
                                forward,
                                firstNode,
                                new LinkedList<>(),
                                visitedById,
                                futureById,
                                handoffFuture,
                                completionFuture,
                                visit
                        );
                    } catch (Throwable t) {
                        if (handoffFuture != null) {
                            handoffFuture.completeExceptionally(t);
                        }
                        if (completionFuture != null) {
                            completionFuture.completeExceptionally(t);
                        }
                        throw Utils.launder(t);
                    }
                }
        );
        futureThreadWalk.add(future);
        return new SagaTraversalResult(saga, pendingWalks, futureThreadWalk, futureById);
    }

    private List<String> traverse(
            AtomicInteger pendingWalks,
            BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk,
            List<String> traversedInThread,
            boolean forward,
            SagaNode node,
            Deque<SagaNode> ancestors,
            ConcurrentMap<String, SagaNode> visitedById,
            ConcurrentMap<String, SelectableFuture<SelectableFuture<Object>>> futureById,
            SelectableFuture<SagaHandoffResult> handoffFuture,
            SelectableFuture<SagaHandoffResult> completionFuture,
            Function<SagaTraversalElement, Object> visit) {

        traversedInThread.add(node.id);

        if (stopSignal.get()) {
            return traversedInThread;
        }

        /*
         * Wait for visitation of all nodes this node depends on to complete
         */
        Map<SagaNode, Object> outputByNode = new LinkedHashMap<>();
        if ((forward ? node.incoming.size() : node.outgoing.size()) > 0) {
            // Add to selector all visitation-futures this node depends on
            FutureSelector<SelectableFuture<Object>, SagaNode> selectorSelector = new FutureSelector<>();
            for (SagaNode dependOnNode : (forward ? node.incoming : node.outgoing)) {
                SelectableFuture<SelectableFuture<Object>> dependOnSimpleFuture = futureById.computeIfAbsent(dependOnNode.id, k -> new SelectableFuture<>(() -> null));
                selectorSelector.add(dependOnSimpleFuture, dependOnNode);
            }
            selectorSelector.add(stopSelectableSelectable, null);
            FutureSelector<Object, SagaNode> selector = new FutureSelector<>();
            while (selectorSelector.moreThanOnePending()) {
                Selection<SelectableFuture<Object>, SagaNode> selected = selectorSelector.select();
                if (stopSignal.get()) {
                    return traversedInThread;
                }
                SelectableFuture<Object> selectableFuture = null;
                try {
                    selectableFuture = selected.future.get();
                } catch (InterruptedException | ExecutionException e) {
                    Utils.launder(e);
                }
                selector.add(selectableFuture, selected.control);
            }
            // Use selector to collect all visitation results
            selector.add(stopSelectable, null);
            while (selector.moreThanOnePending()) {
                Selection<Object, SagaNode> selected = selector.select(); // block until a result is available
                if (stopSignal.get()) {
                    return traversedInThread;
                }
                Object output;
                try {
                    output = selected.future.get(); // will never block
                } catch (InterruptedException | ExecutionException e) {
                    throw Utils.launder(e);
                }
                outputByNode.put(selected.control, output);
            }
        }

        if (stopSignal.get()) {
            return traversedInThread;
        }

        /*
         * Visit this node within the walking thread
         */
        SelectableFuture<SelectableFuture<Object>> futureResult = futureById.computeIfAbsent(node.id, k -> new SelectableFuture<>(() -> null));
        try {
            Object result = visit.apply(new SagaTraversalElement(outputByNode, ancestors, node));
            SelectableFuture<Object> selectableFuture = new SelectableFuture<>(() -> result);
            selectableFuture.run();
            futureResult.complete(selectableFuture);
        } catch (Throwable t) {
            futureResult.completeExceptionally(t);
            throw Utils.launder(t);
        }

        if (stopSignal.get()) {
            return traversedInThread;
        }

        /*
         * Traverse children
         */
        Deque<SagaNode> childAncestors = new LinkedList<>(ancestors);
        childAncestors.addLast(node);
        List<SagaNode> effectiveChildren = new ArrayList<>();
        for (SagaNode child : (forward ? node.outgoing : node.incoming)) {
            if (visitedById.putIfAbsent(child.id, child) != null) {
                continue; // someone else is already traversing this child in parallel
            }
            // first traversal of child
            effectiveChildren.add(child);
        }
        if (effectiveChildren.isEmpty()) {
            return traversedInThread; // no children, or children already being traversed in parallel
        }
        // traverse all but last child asynchronously
        for (int i = 0; i < effectiveChildren.size() - 1; i++) {
            SagaNode child = effectiveChildren.get(i);
            SelectableFuture<List<String>> future = executorService.submit(() -> {
                try {
                    return traverse(pendingWalks, futureThreadWalk, new ArrayList<>(), forward, child, childAncestors, visitedById, futureById, handoffFuture, completionFuture, visit);
                } catch (Throwable t) {
                    if (handoffFuture != null) {
                        handoffFuture.completeExceptionally(t);
                    }
                    if (completionFuture != null) {
                        completionFuture.completeExceptionally(t);
                    }
                    throw Utils.launder(t);
                }
            });
            futureThreadWalk.add(future);
            pendingWalks.incrementAndGet();
        }

        // traverse last child within this thread
        return traverse(pendingWalks, futureThreadWalk, traversedInThread, forward, effectiveChildren.get(effectiveChildren.size() - 1), childAncestors, visitedById, futureById, handoffFuture, completionFuture, visit);
    }
}
