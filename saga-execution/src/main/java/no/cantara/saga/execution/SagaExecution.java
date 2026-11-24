package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.execution.adapter.SagaAdapter;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs executions of one saga on a thread pool, writing every step to a saga log.
 * <p>
 * The pool belongs to the caller. A saga holds a pool thread for every walk that runs an action or waits for a
 * saga-log write or read; none of these waits has a timeout in the engine. A node with several incoming nodes (a join)
 * holds no thread while it waits for them: the walk of the last one starts it, so a pool smaller than the saga's
 * branches makes it slower, not stuck. Size the pool for the sagas that run at the same time, and limit how many run
 * at once in the application. The pool's rejection policy must not run
 * tasks on the submitting thread ({@code ThreadPoolExecutor.CallerRunsPolicy}): such a walk is rejected and fails the
 * saga. A rejected walk fails the saga with the {@link java.util.concurrent.RejectedExecutionException}. Nor may it
 * discard tasks silently ({@code DiscardPolicy}, {@code DiscardOldestPolicy}) while the executor runs: a discarded walk
 * is never ended, and its saga never completes (a walk discarded after shutdown is detected and fails the saga).
 */
public class SagaExecution {

    private final SagaLog sagaLog;
    private final SelectableThreadPoolExecutor executorService;
    private final Saga saga;
    private final AdapterLoader adapterLoader;

    public SagaExecution(SagaLog sagaLog, SelectableThreadPoolExecutor executorService, Saga saga, AdapterLoader adapterLoader) {
        this.sagaLog = sagaLog;
        this.executorService = executorService;
        this.saga = saga;
        this.adapterLoader = adapterLoader;
    }

    public SagaLog getSagaLog() {
        return sagaLog;
    }

    public SelectableThreadPoolExecutor getExecutorService() {
        return executorService;
    }

    public Saga getSaga() {
        return saga;
    }

    public AdapterLoader getAdapterLoader() {
        return adapterLoader;
    }

    /**
     * Executes a saga without {@code preAction} and {@code postAction} callbacks. See
     * {@link #executeSaga(String, Object, boolean, Consumer, Consumer, Consumer)}.
     *
     * @param executionId the id of this execution of the saga; not null
     * @param sagaInput   the data to pass as input to start-node
     * @param recovery    true to recover an execution from the entries in the saga log
     * @param onComplete  called when the saga has completed, before the completion future completes; may be null
     * @return the handoff control of this execution
     */
    public SagaHandoffControl executeSaga(String executionId, Object sagaInput, boolean recovery, Consumer<SagaHandoffResult> onComplete) {
        return executeSaga(executionId, sagaInput, recovery, onComplete,
                c -> {
                }, c -> {
                }
        );
    }

    /**
     * Executes a saga.
     * <p>
     * The {@code onComplete} consumer is called once, when the saga has completed: after the End entry is written
     * on success, after the rollback after an abort, or, after any other failure, once no walk of the saga can still
     * run an action or write to the saga log. It is called on the thread that completes the saga, normally a thread
     * of the executor given to this {@code SagaExecution}. It is called on the thread that called this method, before
     * this method returns, when the saga fails before any walk ran (the executor rejected the first walk), and on the
     * thread that cancels a walk the executor dropped ({@code shutdownNow}). It may block; that holds the thread until
     * it returns.
     * <p>
     * The completion future of the returned {@link SagaHandoffControl} completes only after {@code onComplete}
     * has returned or thrown, so a caller waiting on it can rely on what the consumer has done. It completes:
     * <ul>
     * <li>normally, when the saga succeeded and {@code onComplete} returned;</li>
     * <li>exceptionally with the saga's failure, when the saga failed; an exception thrown by {@code onComplete}
     * is then added to that failure as suppressed;</li>
     * <li>exceptionally with a {@link SagaOnCompleteException}, whose cause is the consumer's exception, when the
     * saga succeeded but {@code onComplete} threw. The saga itself has completed and its End entry is in the log.</li>
     * </ul>
     * A {@code null} consumer is the same as one that does nothing.
     * <p>
     * A walk that the executor rejects (it is shut down, or saturated with {@code AbortPolicy}) fails the saga with the
     * {@link java.util.concurrent.RejectedExecutionException}, through the futures; {@code executeSaga} itself does
     * not throw it. A walk dropped by {@code shutdownNow()} fails the saga when the task returned by
     * {@code shutdownNow()} is cancelled, or when another walk of the saga fails (it is interrupted).
     * <p>
     * In recovery, the log's entries are read on the calling thread, and a failure to read them is thrown here.
     *
     * @param executionId the id of this execution of the saga; not null
     * @param sagaInput   the data to pass as input to start-node
     * @param recovery    true to recover an execution from the entries in the saga log
     * @param onComplete  called when the saga has completed, before the completion future completes; may be null
     * @param preAction   called before each node's action, on the walk's thread; not null
     * @param postAction  called after each node's action, on the walk's thread; not null
     * @return the handoff control of this execution
     * @throws NullPointerException if {@code executionId}, {@code preAction} or {@code postAction} is null
     */
    public SagaHandoffControl executeSaga(String executionId, Object sagaInput, boolean recovery, Consumer<SagaHandoffResult> onComplete, Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(preAction, "preAction");
        Objects.requireNonNull(postAction, "postAction");
        SelectableFuture<SagaHandoffResult> handoffFuture = new SelectableFuture<>(null);
        // completed by the traversal; the completion future given to callers completes after onComplete has run
        SelectableFuture<SagaHandoffResult> traversalCompletionFuture = new SelectableFuture<>(null);
        SelectableFuture<SagaHandoffResult> completionFuture = new SelectableFuture<>(null);
        Map<String, List<SagaLogEntry>> recoverySagaLogEntriesBySagaNodeId = recovery
                ? sagaLog.getSnapshotOfSagaLogEntriesByNodeId(executionId)
                : Collections.emptyMap();
        Execution execution = new Execution(executionId, sagaInput, recovery, recoverySagaLogEntriesBySagaNodeId,
                preAction, postAction, handoffFuture, traversalCompletionFuture);
        traversalCompletionFuture.whenComplete((r, t) -> runOnCompleteThenComplete(executionId, onComplete, r, attachAbort(t, execution.firstAbort.get()), completionFuture));
        SagaTraversalResult traversalResult = execution.forward.forward(execution::visitForward);
        return new SagaHandoffControl(sagaInput, executionId, saga, sagaLog, adapterLoader, traversalResult, handoffFuture, completionFuture,
                () -> execution.state(completionFuture));
    }

    /**
     * One execution of the saga: the forward visit of each node, the abort hand-off, and the rollback.
     */
    private final class Execution {
        final String executionId;
        final Object sagaInput;
        final boolean recovery;
        final Map<String, List<SagaLogEntry>> recoveryEntriesByNodeId;
        final Consumer<SagaExecutionTraversalContext> preAction;
        final Consumer<SagaExecutionTraversalContext> postAction;
        final SelectableFuture<SagaHandoffResult> handoffFuture;
        final SelectableFuture<SagaHandoffResult> completionFuture;
        final SagaWalks walks;
        final SagaTraversal forward;
        // the first abort, so that a saga that fails for another reason at the same time still reports it
        final AtomicReference<AbortSagaException> firstAbort = new AtomicReference<>();
        // the traversal running now: the forward one, then the rollback's; read by state()
        volatile SagaTraversal current;
        volatile boolean rollingBack;

        Execution(String executionId, Object sagaInput, boolean recovery, Map<String, List<SagaLogEntry>> recoveryEntriesByNodeId,
                  Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction,
                  SelectableFuture<SagaHandoffResult> handoffFuture, SelectableFuture<SagaHandoffResult> completionFuture) {
            this.executionId = executionId;
            this.sagaInput = sagaInput;
            this.recovery = recovery;
            this.recoveryEntriesByNodeId = recoveryEntriesByNodeId;
            this.preAction = preAction;
            this.postAction = postAction;
            this.handoffFuture = handoffFuture;
            this.completionFuture = completionFuture;
            this.walks = new SagaWalks(handoffFuture, completionFuture);
            this.forward = new SagaTraversal(executorService, saga, walks);
            this.current = forward;
        }

        /**
         * A snapshot of the execution, for {@link SagaHandoffControl#getState()}.
         */
        SagaExecutionState state(SelectableFuture<SagaHandoffResult> callersCompletionFuture) {
            SagaTraversal traversal = current;
            SagaExecutionState.Phase phase = callersCompletionFuture.isDone() ? SagaExecutionState.Phase.COMPLETED
                    : rollingBack ? SagaExecutionState.Phase.ROLLBACK
                    : SagaExecutionState.Phase.FORWARD;
            return new SagaExecutionState(executionId, phase, traversal.visitedNodeIds(), traversal.runningNodeIds(),
                    traversal.waitingNodeIds(), walks.live(), walks.failure());
        }

        Object visitForward(SagaTraversalElement ste) {
            SagaAdapter<?> adapter = adapterLoader.load(ste.node);

            preAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));

            if (Saga.ID_START.equals(ste.node.id)) {
                if (!recovery) {
                    String serializedSagaInput = sagaInput == null ? null : serialize(adapter, sagaInput);
                    sagaLog.write(sagaLog.builder().startSaga(executionId, saga.name, serializedSagaInput)).join();
                }
                handoffFuture.complete(new SagaHandoffResult(executionId));
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));
                return null;
            }
            if (Saga.ID_END.equals(ste.node.id)) {
                sagaLog.write(sagaLog.builder().endSaga(executionId)).join();
                SagaHandoffResult result = new SagaHandoffResult(executionId);
                completionFuture.complete(result);
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));
                return null;
            }
            // the last entry of the node decides its state: an execution can hold several rounds of the same node
            List<SagaLogEntry> recoveryEntries = recoveryEntriesByNodeId.get(ste.node.id);
            SagaLogEntryType lastEntryType = lastEntryType(recoveryEntries);
            if (lastEntryType == SagaLogEntryType.End) {
                // the action has ended and was not undone since: pass on its latest output
                Optional<String> optionalSerializedOutput = lastEndOutput(recoveryEntries);
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, optionalSerializedOutput.orElse(null)));
                return optionalSerializedOutput
                        .map(jsonData -> (Object) adapter.serializer().deserialize(jsonData))
                        .orElse(null);
            }
            if (lastEntryType != SagaLogEntryType.Start) {
                // a new round: not started, or aborted, or (being) compensated
                sagaLog.write(sagaLog.builder().startAction(executionId, ste.node.id)).join();
            }
            Object actionOutput;
            try {
                actionOutput = adapter.executeAction(ste.node, sagaInput, ste.outputByNode);
            } catch (AbortSagaException e) {
                return abort(ste.node, e);
            }

            String serializedActionOutput = actionOutput == null ? null : serialize(adapter, actionOutput);
            sagaLog.write(sagaLog.builder().endAction(executionId, ste.node.id, serializedActionOutput)).join();

            postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, serializedActionOutput));

            return actionOutput;
        }

        /**
         * The abort hand-off: the first abort stops the forward traversal, writes Abort, and registers the rollback,
         * which starts once every forward walk has ended (so it sees all their entries), on the thread of the last
         * walk to end, unless the saga failed meanwhile. A later abort only writes its Abort entry.
         */
        private Object abort(SagaNode node, AbortSagaException e) {
            firstAbort.compareAndSet(null, e);
            boolean firstToAbort = forward.stopTraversal();
            sagaLog.write(sagaLog.builder().abort(executionId, node.id)).join();
            if (firstToAbort) {
                walks.whenIdle(() -> rollback(e));
            }
            return null;
        }

        /**
         * Rolls the saga back. Runs with a catch-all entry point ({@link SagaWalks}): a failure here, such as a failed
         * read of the log's entries, fails the saga with that failure and the abort suppressed on it.
         */
        private void rollback(AbortSagaException abort) {
            // the backward traversal is current before the phase says ROLLBACK, so a state never pairs them wrongly
            SagaTraversal backward = new SagaTraversal(executorService, saga, walks);
            current = backward;
            rollingBack = true;
            Map<String, List<SagaLogEntry>> sagaLogEntriesBySagaNodeId = sagaLog.getSnapshotOfSagaLogEntriesByNodeId(executionId);
            backward.backwardOnThisThread(ste -> {
                visitBackward(ste, sagaLogEntriesBySagaNodeId, abort);
                return null; // a backward visit has no output
            });
        }

        private void visitBackward(SagaTraversalElement ste, Map<String, List<SagaLogEntry>> sagaLogEntriesBySagaNodeId, AbortSagaException abort) {
            if (Saga.ID_END.equals(ste.node.id)) {
                return;
            }
            if (Saga.ID_START.equals(ste.node.id)) {
                sagaLog.write(sagaLog.builder().endSaga(executionId)).join();
                completionFuture.completeExceptionally(abort);
                return;
            }
            SagaAdapter<?> adapter = adapterLoader.load(ste.node);
            List<SagaLogEntry> sagaLogEntries = sagaLogEntriesBySagaNodeId.get(ste.node.id);
            SagaLogEntryType lastEntryType = lastEntryType(sagaLogEntries);
            if (lastEntryType == null || lastEntryType == SagaLogEntryType.Abort || lastEntryType == SagaLogEntryType.Comp) {
                return; // never started, the aborting node itself, or already compensated
            }
            if (!adapter.hasCompensatingAction()) {
                return; // nothing to undo; its effect stays and no compensation is logged
            }
            // the action started but did not end (last entry Start): it may or may not have run; no output
            Optional<String> serializedOutput = lastEntryType == SagaLogEntryType.End || lastEntryType == SagaLogEntryType.CompStart
                    ? lastEndOutput(sagaLogEntries)
                    : Optional.empty();
            if (lastEntryType != SagaLogEntryType.CompStart) {
                // written before the compensating action runs, so a crash during it leaves the node marked as undone
                sagaLog.write(sagaLog.builder().compStart(executionId, ste.node.id)).join();
            }
            compensate(adapter, ste.node, sagaInput, serializedOutput);
            sagaLog.write(sagaLog.builder().compDone(executionId, ste.node.id)).join();
        }
    }

    @SuppressWarnings("unchecked")
    private static <O> String serialize(SagaAdapter<O> adapter, Object output) {
        return adapter.serializer().serialize((O) output); // the adapter's own output, or the saga input for S
    }

    private static <O> void compensate(SagaAdapter<O> adapter, SagaNode node, Object sagaInput, Optional<String> serializedOutput) {
        O actionOutput = serializedOutput.map(jsonData -> adapter.serializer().deserialize(jsonData)).orElse(null);
        adapter.executeCompensatingAction(node, sagaInput, actionOutput);
    }

    /**
     * A saga that aborted while another node failed with a different exception fails with that exception, and no
     * rollback runs; the abort is attached to it as a suppressed exception so it is not lost.
     */
    static Throwable attachAbort(Throwable sagaFailure, AbortSagaException abort) {
        if (sagaFailure == null || abort == null || sagaFailure == abort) {
            return sagaFailure;
        }
        for (Throwable suppressed : sagaFailure.getSuppressed()) {
            if (suppressed == abort) {
                return sagaFailure;
            }
        }
        sagaFailure.addSuppressed(abort);
        return sagaFailure;
    }

    private static void runOnCompleteThenComplete(String executionId,
                                                  Consumer<SagaHandoffResult> onComplete,
                                                  SagaHandoffResult result,
                                                  Throwable sagaFailure,
                                                  SelectableFuture<SagaHandoffResult> completionFuture) {
        Throwable onCompleteFailure = null;
        if (onComplete != null) {
            try {
                onComplete.accept(sagaFailure == null ? result : new SagaHandoffResult(executionId, sagaFailure));
            } catch (Throwable t) {
                onCompleteFailure = t;
            }
        }
        if (sagaFailure != null) {
            if (onCompleteFailure != null && onCompleteFailure != sagaFailure) {
                sagaFailure.addSuppressed(onCompleteFailure);
            }
            completionFuture.completeExceptionally(sagaFailure);
        } else if (onCompleteFailure != null) {
            completionFuture.completeExceptionally(new SagaOnCompleteException(executionId, onCompleteFailure));
        } else {
            completionFuture.complete(result);
        }
    }

    /**
     * @return the type of the last entry of a node (of the types that describe its state), or null if it has none
     */
    static SagaLogEntryType lastEntryType(List<SagaLogEntry> nodeEntries) {
        if (nodeEntries == null) {
            return null;
        }
        SagaLogEntryType last = null;
        for (SagaLogEntry entry : nodeEntries) {
            if (entry.getEntryType() != SagaLogEntryType.Ignore) {
                last = entry.getEntryType();
            }
        }
        return last;
    }

    /**
     * @return the output of the last End entry of a node
     */
    static Optional<String> lastEndOutput(List<SagaLogEntry> nodeEntries) {
        String output = null;
        if (nodeEntries != null) {
            for (SagaLogEntry entry : nodeEntries) {
                if (entry.getEntryType() == SagaLogEntryType.End) {
                    output = entry.getJsonData();
                }
            }
        }
        return Optional.ofNullable(output);
    }
}
