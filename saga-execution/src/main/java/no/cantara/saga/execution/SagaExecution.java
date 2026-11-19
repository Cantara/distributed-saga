package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
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
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

public class SagaExecution {

    private final SagaLog sagaLog;
    private final SelectableThreadPoolExectutor executorService;
    private final Saga saga;
    private final AdapterLoader adapterLoader;

    public SagaExecution(SagaLog sagaLog, SelectableThreadPoolExectutor executorService, Saga saga, AdapterLoader adapterLoader) {
        this.sagaLog = sagaLog;
        this.executorService = executorService;
        this.saga = saga;
        this.adapterLoader = adapterLoader;
    }

    public SagaLog getSagaLog() {
        return sagaLog;
    }

    public SelectableThreadPoolExectutor getExecutorService() {
        return executorService;
    }

    public Saga getSaga() {
        return saga;
    }

    public AdapterLoader getAdapterLoader() {
        return adapterLoader;
    }

    /**
     * @param sagaInput the data to pass as input to start-node.
     * @param recovery
     * @return
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
     * on success, or after rollback (or a failure of the traversal) otherwise. It is called on the thread that
     * completes the saga, a thread of the executor given to this {@code SagaExecution} (unless that executor's
     * rejection policy runs tasks on the submitting thread). It may block; that holds the executor thread until it
     * returns.
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
     *
     * @param executionId the id of this execution of the saga
     * @param sagaInput   the data to pass as input to start-node
     * @param recovery    true to recover an execution from the entries in the saga log
     * @param onComplete  called when the saga has completed, before the completion future completes
     * @param preAction   called before each node's action
     * @param postAction  called after each node's action
     * @return the handoff control of this execution
     */
    public SagaHandoffControl executeSaga(String executionId, Object sagaInput, boolean recovery, Consumer<SagaHandoffResult> onComplete, Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction) {
        SelectableFuture<SagaHandoffResult> handoffFuture = new SelectableFuture<>(null);
        // completed by the traversal; the completion future given to callers completes after onComplete has run
        SelectableFuture<SagaHandoffResult> traversalCompletionFuture = new SelectableFuture<>(null);
        SelectableFuture<SagaHandoffResult> completionFuture = new SelectableFuture<>(null);
        // the first abort, so that a saga that fails for another reason at the same time still reports it
        AtomicReference<AbortSagaException> firstAbort = new AtomicReference<>();
        traversalCompletionFuture.whenComplete((r, t) -> runOnCompleteThenComplete(executionId, onComplete, r, attachAbort(t, firstAbort.get()), completionFuture));
        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        CompletableFuture<SagaTraversalResult> futureTraversalResult = new CompletableFuture<>();
        Map<String, List<SagaLogEntry>> recoverySagaLogEntriesBySagaNodeId;
        if (recovery) {
            recoverySagaLogEntriesBySagaNodeId = sagaLog.getSnapshotOfSagaLogEntriesByNodeId(executionId);
        } else {
            recoverySagaLogEntriesBySagaNodeId = Collections.emptyMap();
        }
        SagaTraversalResult traversalResult = sagaTraversal.forward(handoffFuture, traversalCompletionFuture, ste -> {
            SagaAdapter adapter = adapterLoader.load(ste.node);

            preAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));

            if (Saga.ID_START.equals(ste.node.id)) {
                if (!recovery) {
                    String serializedSagaInput = ofNullable(sagaInput)
                            .map(i -> adapter.serializer().serialize(i)) // safe unchecked call
                            .orElse(null);
                    sagaLog.write(sagaLog.builder().startSaga(executionId, saga.name, serializedSagaInput)).join();
                }
                handoffFuture.complete(new SagaHandoffResult(executionId));
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));
                return null;
            }
            if (Saga.ID_END.equals(ste.node.id)) {
                sagaLog.write(sagaLog.builder().endSaga(executionId)).join();
                SagaHandoffResult result = new SagaHandoffResult(executionId);
                traversalCompletionFuture.complete(result);
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, null));
                return null;
            }
            // the last entry of the node decides its state: an execution can hold several rounds of the same node
            SagaLogEntryType lastEntryType = lastEntryType(recoverySagaLogEntriesBySagaNodeId.get(ste.node.id));
            if (lastEntryType == SagaLogEntryType.End) {
                // the action has ended and was not undone since: pass on its latest output
                Optional<String> optionalSerializedOutput = lastEndOutput(recoverySagaLogEntriesBySagaNodeId.get(ste.node.id));
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, optionalSerializedOutput.orElse(null)));
                Object output = optionalSerializedOutput
                        .map(jsonData -> adapter.serializer().deserialize(jsonData))
                        .orElse(null);
                return output;
            }
            if (lastEntryType != SagaLogEntryType.Start) {
                // a new round: not started, or aborted, or (being) compensated
                sagaLog.write(sagaLog.builder().startAction(executionId, ste.node.id)).join();
            }
            Object actionOutput;
            try {
                actionOutput = adapter.executeAction(ste.node, sagaInput, ste.outputByNode);

            } catch (AbortSagaException e) {
                firstAbort.compareAndSet(null, e);
                boolean firstToAbort = sagaTraversal.stopTraversal();
                sagaLog.write(sagaLog.builder().abort(executionId, ste.node.id)).join();
                if (!firstToAbort) {
                    return null; // More than one abort, let only first abort trigger rollback-recovery
                }
                SagaTraversalResult sagaTraversalResult = futureTraversalResult.join();

                executorService.submit(() -> {
                    // ensure we catch all saga-log entries of forward traversal before running recovery
                    sagaTraversalResult.waitForThreadWalksToComplete();

                    rollbackRecovery(e, executionId, sagaInput, traversalCompletionFuture, sagaTraversalResult.pendingWalks, sagaTraversalResult.futureThreadWalk, new ConcurrentHashMap<>());
                });

                return null;
            }

            String serializedActionOutput = ofNullable(actionOutput)
                    .map(o -> adapter.serializer().serialize(o)) // safe unchecked call
                    .orElse(null);
            sagaLog.write(sagaLog.builder().endAction(executionId, ste.node.id, serializedActionOutput)).join();

            postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, serializedActionOutput));

            return actionOutput;
        });
        futureTraversalResult.complete(traversalResult);

        return new SagaHandoffControl(sagaInput, executionId, saga, sagaLog, adapterLoader, traversalResult, handoffFuture, completionFuture);
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

    private void rollbackRecovery(AbortSagaException exception,
                                  String executionId,
                                  Object sagaInput,
                                  SelectableFuture<SagaHandoffResult> completionFuture,
                                  AtomicInteger pendingWalks,
                                  BlockingQueue<SelectableFuture<List<String>>> futureThreadWalk,
                                  ConcurrentHashMap<String, SelectableFuture<SelectableFuture<Object>>> futureById) {
        Map<String, List<SagaLogEntry>> sagaLogEntriesBySagaNodeId = sagaLog.getSnapshotOfSagaLogEntriesByNodeId(executionId);
        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        sagaTraversal.backward(null, completionFuture, pendingWalks, futureThreadWalk, futureById, ste -> {
            if (Saga.ID_END.equals(ste.node.id)) {
                return null;
            }
            if (Saga.ID_START.equals(ste.node.id)) {
                sagaLog.write(sagaLog.builder().endSaga(executionId)).join();
                completionFuture.completeExceptionally(exception);
                return null;
            }
            SagaAdapter adapter = adapterLoader.load(ste.node);
            List<SagaLogEntry> sagaLogEntries = sagaLogEntriesBySagaNodeId.get(ste.node.id);
            SagaLogEntryType lastEntryType = lastEntryType(sagaLogEntries);
            if (lastEntryType == null || lastEntryType == SagaLogEntryType.Abort || lastEntryType == SagaLogEntryType.Comp) {
                return null; // never started, the aborting node itself, or already compensated
            }
            if (!adapter.hasCompensatingAction()) {
                return null; // nothing to undo; its effect stays and no compensation is logged
            }
            Object actionOutput = null; // the action started but did not end: it may or may not have run
            if (lastEntryType == SagaLogEntryType.End || lastEntryType == SagaLogEntryType.CompStart) {
                actionOutput = lastEndOutput(sagaLogEntries)
                        .map(jsonData -> adapter.serializer().deserialize(jsonData))
                        .orElse(null);
            }
            if (lastEntryType != SagaLogEntryType.CompStart) {
                // written before the compensating action runs, so a crash during it leaves the node marked as undone
                sagaLog.write(sagaLog.builder().compStart(executionId, ste.node.id)).join();
            }
            adapter.executeCompensatingAction(ste.node, sagaInput, actionOutput); // safe unchecked call
            sagaLog.write(sagaLog.builder().compDone(executionId, ste.node.id)).join();
            return null; // no output from running compensating action.
        });
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
