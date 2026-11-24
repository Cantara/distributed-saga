package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.concurrent.futureselector.Utils;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One traversal of a saga's graph, forward or backward: one walk at the first node, one more walk per extra branch.
 * <p>
 * No walk waits for another. Each node counts the nodes it depends on that have not yet been visited (forward: its
 * incoming nodes; backward: its outgoing nodes). A walk that has visited a node signals each node that depends on it,
 * and the signal that brings a node's count to zero starts that node: on the same walk for one of them, on a new walk
 * for each other. So a join is started by the walk of the last node it depends on, and holds no pool thread while it
 * waits; a pool smaller than the saga's branches makes the saga slower, not stuck.
 * <p>
 * A visit whose step runs asynchronously returns a {@link Pending}: the walk ends, and the step is counted as a walk
 * from then on. The thread that completes the stage only submits a new walk to the pool, which takes over that count,
 * runs the rest of the visit and goes on from there. So the thread that completes the stage never ends a count, and
 * never finds the saga idle: the rollback, or the completion of a failed saga, never runs on it (unless the pool
 * rejects the new walk: it is shut down). That walk is never dropped when the traversal stops, so a step that ran is
 * always finished (its End entry written), as a synchronous step is.
 * <p>
 * Every walk is counted in the execution's {@link SagaWalks} from just before it is submitted until it has ended or
 * is known never to run. A walk that fails stops the traversal (se-1): no walk starts a new node, and the nodes that
 * depend on the failed one are never signalled. A walk the executor rejects, or that it drops and the caller cancels,
 * fails the saga (se-4). A walk that the executor would run on the thread that submits it (a caller-runs rejection
 * policy) is rejected instead (se-5).
 */
class SagaTraversal {

    private final SelectableThreadPoolExecutor executorService;
    private final Saga saga;
    private final SagaWalks walks;
    private final AtomicBoolean stopSignal = new AtomicBoolean(false);
    // walks submitted but not started; claimed by whoever stops the traversal, as they would do nothing
    private final Set<Walk> notStarted = ConcurrentHashMap.newKeySet();
    // the traversal's state, once it has started; read by getState()
    private volatile Graph graph;

    SagaTraversal(SelectableThreadPoolExecutor executorService, Saga saga) {
        this(executorService, saga, new SagaWalks(null, null));
    }

    SagaTraversal(SelectableThreadPoolExecutor executorService, Saga saga, SagaWalks walks) {
        this.executorService = executorService;
        this.saga = saga;
        this.walks = walks;
    }

    /**
     * Stops the traversal: no walk starts a new node. The walks that were submitted but have not started are ended at
     * once.
     *
     * @return true iff the calling thread was the first to stop the traversal.
     */
    boolean stopTraversal() {
        boolean firstToStop = stopSignal.compareAndSet(false, true);
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

    /**
     * @return the ids of the nodes whose visit has ended normally
     */
    Set<String> visitedNodeIds() {
        Graph g = graph;
        Set<String> visited = new java.util.HashSet<>();
        if (g != null) {
            g.futureById.forEach((id, future) -> {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    visited.add(id);
                }
            });
        }
        return visited;
    }

    /**
     * @return the ids of the nodes being visited now
     */
    Set<String> runningNodeIds() {
        Graph g = graph;
        return g == null ? Set.of() : new java.util.HashSet<>(g.running);
    }

    /**
     * @return for each node that has had some, but not all, of its signals: how many it still waits for
     */
    Map<String, Integer> waitingNodeIds() {
        Graph g = graph;
        Map<String, Integer> waiting = new LinkedHashMap<>();
        if (g != null) {
            g.waitingById.forEach((id, count) -> {
                int left = count.get();
                if (left > 0) {
                    waiting.put(id, left);
                }
            });
        }
        return waiting;
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
        Graph graph = new Graph(forward, visit);
        this.graph = graph;
        Walk first = new Walk(graph, firstNode, new LinkedList<>());
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
        return new SagaTraversalResult(saga, graph.futureThreadWalk, graph.futureById);
    }

    /**
     * What a visit returns when its step runs asynchronously: the step's stage, and the rest of the visit, which runs on
     * the pool with the stage's value or failure once the stage has completed, and returns the node's output or throws.
     */
    static final class Pending {
        final CompletionStage<?> stage;
        final BiFunction<Object, Throwable, Object> continuation;

        Pending(CompletionStage<?> stage, BiFunction<Object, Throwable, Object> continuation) {
            this.stage = stage;
            this.continuation = continuation;
        }
    }

    /**
     * The state of one traversal that its walks share.
     */
    private static final class Graph {
        final boolean forward;
        final Function<SagaTraversalElement, Object> visit;
        // per node: the nodes it depends on that have not signalled yet; created by the first signal
        final ConcurrentMap<String, AtomicInteger> waitingById = new ConcurrentHashMap<>();
        // per node: the ancestors of the first node to signal it, which become the node's own ancestors
        final ConcurrentMap<String, Deque<SagaNode>> ancestorsById = new ConcurrentHashMap<>();
        // per node: the result of its visit, completed before the node signals the nodes that depend on it
        final ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> futureById = new ConcurrentHashMap<>();
        final BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk = new LinkedBlockingQueue<>();
        // the nodes being visited now
        final Set<String> running = ConcurrentHashMap.newKeySet();

        Graph(boolean forward, Function<SagaTraversalElement, Object> visit) {
            this.forward = forward;
            this.visit = visit;
        }

        Collection<SagaNode> dependencies(SagaNode node) {
            return forward ? node.incoming : node.outgoing;
        }

        Collection<SagaNode> dependents(SagaNode node) {
            return forward ? node.outgoing : node.incoming;
        }

        /**
         * Signals a node that one of the nodes it depends on, the signaller, has been visited. The first signal gives
         * the node its ancestors: the signaller's, and the signaller.
         *
         * @return true for the one signal that was the last the node waited for
         */
        boolean signal(SagaNode node, SagaNode signaller, Deque<SagaNode> signallerAncestors) {
            ancestorsById.computeIfAbsent(node.id, id -> {
                Deque<SagaNode> ancestors = new LinkedList<>(signallerAncestors);
                ancestors.addLast(signaller);
                return ancestors;
            });
            AtomicInteger waiting = waitingById.computeIfAbsent(node.id, id -> new AtomicInteger(dependencies(node).size()));
            return waiting.decrementAndGet() == 0;
        }

        /**
         * The outputs of the nodes a node depends on. Called only once they have all signalled, so every visit
         * future is done: nothing here waits.
         */
        Map<SagaNode, Object> outputsOfDependencies(SagaNode node) {
            Map<SagaNode, Object> outputByNode = new LinkedHashMap<>();
            for (SagaNode dependency : dependencies(node)) {
                SelectableFuture<SelectableFuture<Object>> visited = futureById.get(dependency.id);
                if (visited == null || !visited.isDone()) {
                    throw new IllegalStateException("node " + node.id + " was started before " + dependency.id
                            + ", a node it depends on, was visited");
                }
                outputByNode.put(dependency, visited.join().join()); // done and successful: never blocks or throws
            }
            return outputByNode;
        }
    }

    /**
     * Submits a walk, counted until it ends. A rejected walk fails the saga and is rethrown. A walk that continues a
     * step that ran is not dropped when the traversal stops: it finishes the step.
     */
    private void submit(Walk walk) {
        if (walk.resume == null) {
            walks.started();
            notStarted.add(walk);
        } // else: a walk that finishes an asynchronous step takes over the count the step holds
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
        walk.graph.futureThreadWalk.add(future);
    }

    private final class Walk implements Callable<List<String>> {
        final Graph graph;
        final SagaNode node;
        final Deque<SagaNode> ancestors;
        // the rest of the node's visit, once its asynchronous step has completed; null for a walk that visits the node
        final Supplier<Object> resume;
        volatile Thread submitter; // the thread inside submit(); a walk run on it runs inline
        volatile int claimed;

        Walk(Graph graph, SagaNode node, Deque<SagaNode> ancestors) {
            this(graph, node, ancestors, null);
        }

        Walk(Graph graph, SagaNode node, Deque<SagaNode> ancestors, Supplier<Object> resume) {
            this.graph = graph;
            this.node = node;
            this.ancestors = ancestors;
            this.resume = resume;
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
                return resume == null
                        ? traverse(new ArrayList<>(), graph, node, ancestors)
                        : resumed(new ArrayList<>(), graph, node, ancestors, resume);
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

    /**
     * Visits a node whose dependencies have all been visited, then signals the nodes that depend on it, and goes on
     * with those that the signal started.
     */
    private List<String> traverse(List<String> traversedInThread, Graph graph, SagaNode node, Deque<SagaNode> ancestors) {

        traversedInThread.add(node.id);

        if (stopSignal.get()) {
            return traversedInThread;
        }

        /*
         * Visit this node within the walking thread
         */
        Map<SagaNode, Object> outputByNode = graph.outputsOfDependencies(node);
        graph.futureById.computeIfAbsent(node.id, k -> new SelectableFuture<>(() -> null));
        graph.running.add(node.id);
        Object result;
        try {
            result = graph.visit.apply(new SagaTraversalElement(outputByNode, ancestors, node));
        } catch (Throwable t) {
            throw visitFailed(graph, node, t);
        }
        if (result instanceof Pending pending) {
            resumeWhenDone(graph, node, ancestors, pending);
            return traversedInThread; // this walk ends; a new one finishes the visit when the step has completed
        }
        return visited(traversedInThread, graph, node, ancestors, result);
    }

    /**
     * Finishes the visit of a node whose asynchronous step has completed, on the walk the stage's completion submitted,
     * and goes on from there.
     */
    private List<String> resumed(List<String> traversedInThread, Graph graph, SagaNode node, Deque<SagaNode> ancestors, Supplier<Object> resume) {
        Object result;
        try {
            result = resume.get();
        } catch (Throwable t) {
            throw visitFailed(graph, node, t);
        }
        return visited(traversedInThread, graph, node, ancestors, result);
    }

    /**
     * A visit failed: the node's visit future fails with it, and the walk fails.
     */
    private static RuntimeException visitFailed(Graph graph, SagaNode node, Throwable t) {
        graph.running.remove(node.id);
        graph.futureById.get(node.id).completeExceptionally(t);
        return Utils.launder(t);
    }

    /**
     * Counts the node's asynchronous step as a walk, and has the stage's completion submit a walk that finishes the
     * visit. On the thread that completes the stage, nothing else runs: the saga continues on its pool.
     */
    private void resumeWhenDone(Graph graph, SagaNode node, Deque<SagaNode> ancestors, Pending pending) {
        walks.started(); // the step counts as a walk; the walk that finishes it takes the count over
        try {
            pending.stage.whenComplete((value, failure) -> {
                try {
                    submit(new Walk(graph, node, ancestors, () -> pending.continuation.apply(value, failure)));
                } catch (Throwable rejected) {
                    // submit has failed the saga and ended the count; the node's visit fails with it
                    graph.running.remove(node.id);
                    graph.futureById.get(node.id).completeExceptionally(rejected);
                }
            });
        } catch (Throwable t) { // a stage that refuses the callback
            graph.running.remove(node.id);
            graph.futureById.get(node.id).completeExceptionally(t);
            walkFailed(t);
            walks.ended();
        }
    }

    /**
     * The node's visit has ended with its output: completes its visit future, then signals the nodes that depend on it,
     * and goes on with those that the signal started.
     */
    private List<String> visited(List<String> traversedInThread, Graph graph, SagaNode node, Deque<SagaNode> ancestors, Object result) {
        SelectableFuture<Object> selectableFuture = new SelectableFuture<>(() -> result);
        selectableFuture.run();
        graph.futureById.get(node.id).complete(selectableFuture);
        graph.running.remove(node.id);

        if (stopSignal.get()) {
            return traversedInThread;
        }

        /*
         * Signal the nodes that depend on this one; a node that has now had every signal it waits for is started
         */
        List<SagaNode> started = new ArrayList<>();
        for (SagaNode dependent : graph.dependents(node)) {
            if (graph.signal(dependent, node, ancestors)) {
                started.add(dependent);
            }
        }
        if (started.isEmpty()) {
            return traversedInThread; // no dependents, or they wait for other nodes, whose walks will start them
        }
        // all but the last on new walks; each walk has its own ancestors, not shared with its siblings
        for (int i = 0; i < started.size() - 1; i++) {
            SagaNode dependent = started.get(i);
            submit(new Walk(graph, dependent, graph.ancestorsById.get(dependent.id)));
        }

        // the last within this walk
        SagaNode last = started.get(started.size() - 1);
        return traverse(traversedInThread, graph, last, graph.ancestorsById.get(last.id));
    }
}
