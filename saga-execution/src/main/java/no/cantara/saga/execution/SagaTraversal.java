package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.FutureSelector;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.concurrent.futureselector.Selection;
import no.cantara.concurrent.futureselector.Utils;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

/**
 * One traversal of a saga's graph, forward or backward: one walk at the first node, one more walk per extra branch,
 * a join waiting on its pool thread for the nodes it depends on.
 * <p>
 * Every walk is counted in the execution's {@link SagaWalks} from just before it is submitted until it has ended or
 * is known never to run. A walk that fails stops the traversal (se-1): walks waiting at a join are released, and no
 * walk starts a new node. A walk the executor rejects, or that it drops and the caller cancels, fails the saga
 * (se-4). A walk that the executor would run on the thread that submits it (a caller-runs rejection policy) is
 * rejected instead, since that thread may be the one the walk waits for (se-5).
 */
class SagaTraversal {

    private final SelectableThreadPoolExecutor executorService;
    private final Saga saga;
    private final SagaWalks walks;
    private final AtomicBoolean stopSignal = new AtomicBoolean(false);
    private final SelectableFuture<Object> stopSelectable = new SelectableFuture<>(() -> null);
    private final SelectableFuture<SelectableFuture<Object>> stopSelectableSelectable = new SelectableFuture<>(() -> null);
    // walks submitted but not started; claimed by whoever stops the traversal, as they would do nothing
    private final Set<Walk> notStarted = ConcurrentHashMap.newKeySet();

    SagaTraversal(SelectableThreadPoolExecutor executorService, Saga saga) {
        this(executorService, saga, new SagaWalks(null, null));
    }

    SagaTraversal(SelectableThreadPoolExecutor executorService, Saga saga, SagaWalks walks) {
        this.executorService = executorService;
        this.saga = saga;
        this.walks = walks;
    }

    /**
     * Stops the traversal: walks waiting at a join return, and no walk starts a new node. The walks that were
     * submitted but have not started are ended at once.
     *
     * @return true iff the calling thread was the first to stop the traversal.
     */
    boolean stopTraversal() {
        boolean firstToStop = stopSignal.compareAndSet(false, true);
        if (firstToStop) {
            stopSelectable.complete(null);
            stopSelectableSelectable.complete(null);
        }
        endNotStartedWalks();
        return firstToStop;
    }

    private void endNotStartedWalks() {
        for (Walk walk : notStarted) {
            if (walk.claim()) {
                notStarted.remove(walk);
                walks.ended();
            }
        }
    }

    /**
     * A walk failed: the failure is the saga's (the first one wins), and the traversal stops.
     */
    private void walkFailed(Throwable t) {
        walks.fail(t);
        stopTraversal();
    }

    SagaTraversalResult forward(Function<SagaTraversalElement, Object> visit) {
        return traverse(true, saga.getStartNode(), visit, false);
    }

    SagaTraversalResult backward(Function<SagaTraversalElement, Object> visit) {
        return traverse(false, saga.getEndNode(), visit, false);
    }

    /**
     * Starts the backward traversal on the calling thread, which must be counted in the execution's walks: the
     * first walk runs inline, further walks on the pool.
     */
    SagaTraversalResult backwardOnThisThread(Function<SagaTraversalElement, Object> visit) {
        return traverse(false, saga.getEndNode(), visit, true);
    }

    private SagaTraversalResult traverse(boolean forward, SagaNode firstNode, Function<SagaTraversalElement, Object> visit, boolean inline) {
        BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk = new LinkedBlockingQueue<>();
        ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> futureById = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, SagaNode> visitedById = new ConcurrentHashMap<>();
        visitedById.putIfAbsent(firstNode.id, firstNode);
        Walk first = new Walk(forward, firstNode, new LinkedList<>(), visitedById, futureById, futureThreadWalk, visit);
        if (inline) {
            walks.started();
            first.claim();
            first.run();
        } else {
            try {
                submit(first);
            } catch (RuntimeException e) {
                // rejected: the failure travels through the saga's futures, as any other failure does
            }
        }
        return new SagaTraversalResult(saga, futureThreadWalk, futureById);
    }

    /**
     * Submits a walk, counted until it ends. A rejected walk fails the saga and is rethrown.
     */
    private void submit(Walk walk) {
        walks.started();
        notStarted.add(walk);
        walk.submitter = Thread.currentThread();
        SelectableFuture<List<String>> future;
        try {
            future = executorService.submit(walk);
        } catch (RuntimeException e) {
            if (walk.claim()) {
                notStarted.remove(walk);
                walkFailed(e);
                walks.ended();
            }
            throw e;
        } finally {
            walk.submitter = null;
        }
        if (executorService.isShutdown() && !executorService.getQueue().contains(future) && walk.claim()) {
            // a rejection policy that discards silently once the executor is shut down (CallerRunsPolicy, or
            // DiscardPolicy): the walk will never run
            notStarted.remove(walk);
            RejectedExecutionException rejected = new RejectedExecutionException("a walk of saga " + saga.name
                    + " at node " + walk.node.id + " was discarded: the executor is shut down");
            walkFailed(rejected);
            walks.ended();
            throw rejected;
        }
        // a walk future that fails without the walk having run (cancelled after shutdownNow) fails the saga
        future.whenComplete((result, failure) -> {
            if (failure != null && walk.claim()) {
                notStarted.remove(walk);
                walkFailed(failure);
                walks.ended();
            }
        });
        walk.futureThreadWalk.add(future);
    }

    private final class Walk implements Callable<List<String>> {
        final boolean forward;
        final SagaNode node;
        final Deque<SagaNode> ancestors;
        final ConcurrentMap<String, SagaNode> visitedById;
        final ConcurrentMap<String, SelectableFuture<SelectableFuture<Object>>> futureById;
        final BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk;
        final Function<SagaTraversalElement, Object> visit;
        volatile Thread submitter; // the thread inside submit(); a walk run on it runs inline
        volatile int claimed;

        Walk(boolean forward, SagaNode node, Deque<SagaNode> ancestors, ConcurrentMap<String, SagaNode> visitedById,
             ConcurrentMap<String, SelectableFuture<SelectableFuture<Object>>> futureById,
             BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk, Function<SagaTraversalElement, Object> visit) {
            this.forward = forward;
            this.node = node;
            this.ancestors = ancestors;
            this.visitedById = visitedById;
            this.futureById = futureById;
            this.futureThreadWalk = futureThreadWalk;
            this.visit = visit;
        }

        /**
         * @return true for the one caller that decides the walk's fate: run it, or end it without running
         */
        boolean claim() {
            return CLAIMED.compareAndSet(this, 0, 1);
        }

        @Override
        public List<String> call() {
            if (!claim()) {
                return Collections.emptyList(); // ended without running: the traversal stopped, or it was cancelled
            }
            notStarted.remove(this);
            if (submitter == Thread.currentThread()) {
                RejectedExecutionException rejected = new RejectedExecutionException("a walk of saga " + saga.name
                        + " at node " + node.id + " was to run on the thread that submitted it; the executor's "
                        + "rejection policy must not run tasks on the submitting thread");
                try {
                    walkFailed(rejected);
                } finally {
                    walks.ended();
                }
                throw rejected;
            }
            return run();
        }

        /**
         * The walk's entry point: a failure stops the traversal and fails the saga; the walk is ended in any case.
         */
        List<String> run() {
            try {
                return traverse(new ArrayList<>(), forward, node, ancestors, visitedById, futureById, futureThreadWalk, visit);
            } catch (Throwable t) {
                walkFailed(t);
                throw Utils.launder(t);
            } finally {
                if (stopSignal.get()) {
                    endNotStartedWalks(); // walks submitted after the stop, which would do nothing
                }
                walks.ended();
            }
        }
    }

    private static final AtomicIntegerFieldUpdater<Walk> CLAIMED = AtomicIntegerFieldUpdater.newUpdater(Walk.class, "claimed");

    private List<String> traverse(
            List<String> traversedInThread,
            boolean forward,
            SagaNode node,
            Deque<SagaNode> ancestors,
            ConcurrentMap<String, SagaNode> visitedById,
            ConcurrentMap<String, SelectableFuture<SelectableFuture<Object>>> futureById,
            BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk,
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
                SelectableFuture<Object> selectableFuture;
                try {
                    selectableFuture = selected.future.join(); // done: never blocks
                } catch (CompletionException e) {
                    throw dependencyFailure(e); // a node this one depends on failed: end this walk
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
                    output = selected.future.join(); // done: never blocks
                } catch (CompletionException e) {
                    throw dependencyFailure(e);
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
        // traverse all but last child asynchronously; each walk has its own ancestors, not shared with its siblings
        for (int i = 0; i < effectiveChildren.size() - 1; i++) {
            Deque<SagaNode> childAncestors = new LinkedList<>(ancestors);
            childAncestors.addLast(node);
            submit(new Walk(forward, effectiveChildren.get(i), childAncestors, visitedById, futureById, futureThreadWalk, visit));
        }

        // traverse last child within this thread
        Deque<SagaNode> childAncestors = new LinkedList<>(ancestors);
        childAncestors.addLast(node);
        return traverse(traversedInThread, forward, effectiveChildren.get(effectiveChildren.size() - 1), childAncestors, visitedById, futureById, futureThreadWalk, visit);
    }

    /**
     * The failure of a node this walk depends on, as that node's walk threw it. Every walk that sees a failure
     * reports the same exception, so the saga fails with it, whichever of them is first.
     */
    private static RuntimeException dependencyFailure(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return Utils.launder(cause == null ? e : cause);
    }
}
