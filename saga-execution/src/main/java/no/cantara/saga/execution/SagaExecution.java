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
        traversalCompletionFuture.whenComplete((r, t) -> runOnCompleteThenComplete(executionId, onComplete, r, t, completionFuture));
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
            List<SagaLogEntry> sagaLogEntries = recoverySagaLogEntriesBySagaNodeId.get(ste.node.id);
            if (sagaLogEntries == null || sagaLogEntries.isEmpty()) {
                sagaLog.write(sagaLog.builder().startAction(executionId, ste.node.id)).join();
            }
            if (sagaLogEntries != null && sagaLogEntries.stream().anyMatch(e -> SagaLogEntryType.End == e.getEntryType())) {
                // action was already executed, return output from saga-log
                Optional<String> optionalSerializedOutput = sagaLogEntries.stream()
                        .filter(e -> SagaLogEntryType.End == e.getEntryType())
                        .findFirst()
                        .map(sle -> sle.getJsonData());
                postAction.accept(new SagaExecutionTraversalContext(ste.outputByNode, ste.ancestors, ste.node, adapter, optionalSerializedOutput.orElse(null)));
                Object output = optionalSerializedOutput
                        .map(jsonData -> adapter.serializer().deserialize(jsonData))
                        .orElse(null);
                return output;
            }
            Object actionOutput;
            try {
                actionOutput = adapter.executeAction(ste.node, sagaInput, ste.outputByNode);

            } catch (AbortSagaException e) {
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
            if (sagaLogEntries == null || sagaLogEntries.isEmpty()) {
                return null;
            }
            if (sagaLogEntries != null && sagaLogEntries.stream().anyMatch(e -> SagaLogEntryType.Abort == e.getEntryType())) {
                return null; // abort action
            }
            Object actionOutput;
            if (sagaLogEntries != null && sagaLogEntries.stream().anyMatch(e -> SagaLogEntryType.End == e.getEntryType())) {
                // action was already executed, use its output to cancel
                actionOutput = sagaLogEntries.stream()
                        .filter(e -> SagaLogEntryType.End == e.getEntryType())
                        .findFirst()
                        .map(sle -> sle.getJsonData())
                        .map(jsonData -> adapter.serializer().deserialize(jsonData))
                        .orElse(null);
            } else {
                // Unknown whether action was executed or not, execute it (possibly again)
                // to ensure consistency and to update saga-log with end-action and output.
                Map<SagaNode, Object> dependeesOutput = getDependeesOutputByNode(sagaLogEntriesBySagaNodeId, ste.node, adapter);
                actionOutput = adapter.executeAction(ste.node, sagaInput, dependeesOutput);
                String serializedActionOutput = adapter.serializer().serialize(actionOutput); // safe unchecked call
                sagaLog.write(sagaLog.builder().endAction(executionId, ste.node.id, serializedActionOutput)).join();
            }
            adapter.executeCompensatingAction(ste.node, sagaInput, actionOutput); // safe unchecked call
            sagaLog.write(sagaLog.builder().compDone(executionId, ste.node.id)).join();
            return null; // no output from running compensating action.
        });
    }

    private Map<SagaNode, Object> getDependeesOutputByNode
            (Map<String, List<SagaLogEntry>> sagaLogEntriesBySagaNodeId, SagaNode node, SagaAdapter adapter) {
        Map<SagaNode, Object> dependeesOutputByNode = null;
        for (SagaNode dependeeNode : node.incoming) {
            List<SagaLogEntry> dependeeEntries = sagaLogEntriesBySagaNodeId.get(dependeeNode.id);
            Object dependeeOutput = dependeeEntries.stream()
                    .filter(e -> SagaLogEntryType.End == e.getEntryType())
                    .findFirst()
                    .map(sle -> sle.getJsonData())
                    .map(jsonData -> adapter.serializer().deserialize(jsonData))
                    .orElse(null);
            dependeesOutputByNode.put(dependeeNode, dependeeOutput);
        }
        return dependeesOutputByNode;
    }
}
