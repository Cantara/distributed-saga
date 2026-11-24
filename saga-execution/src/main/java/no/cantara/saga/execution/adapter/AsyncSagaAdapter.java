package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.SagaNode;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * An adapter whose action and compensating action run asynchronously: each returns a {@link CompletionStage} that
 * completes when the work is done, and holds no pool thread of the saga meanwhile. Register it with the
 * {@link AdapterLoader} like any adapter; the engine calls {@link #executeActionAsync} and
 * {@link #executeCompensatingActionAsync}, never the synchronous methods, which an asynchronous adapter does not
 * implement.
 * <p>
 * What the engine promises:
 * <ul>
 * <li>{@code Start} is written to the saga log, durably, before {@code executeActionAsync} is called; {@code End}
 * after the stage has completed, before the nodes that depend on this one start. For a compensation, {@code CompStart}
 * before {@code executeCompensatingActionAsync}, {@code Comp} after its stage.</li>
 * <li>The saga continues on its own pool, never on the thread that completes the stage: that thread only hands the
 * continuation to the pool. So the log write, the {@code postAction} and the next nodes never run on the caller's
 * threads (an RxJava scheduler, a database driver's threads).</li>
 * <li>The engine only reads the stage: it never completes, cancels or blocks on it, and attaches one callback.</li>
 * <li>A stage that completes exceptionally with an {@link AbortSagaException} (also inside a
 * {@code CompletionException}) aborts and rolls the saga back, as a thrown one does; any other failure fails the saga
 * with that exception (a checked one inside a {@code CompletionException}).</li>
 * <li>While the stage has not completed, the step counts as running: a rollback, or the completion of a failed saga,
 * waits for it. A stage that never completes keeps the saga open, as a synchronous action that never returns does;
 * timeouts are the application's, for example {@code orTimeout} on the stage.</li>
 * </ul>
 * The methods may also throw, synchronously, as a synchronous adapter does: {@code AbortSagaException} to abort, any
 * other exception to fail the saga. They must not return null.
 * <p>
 * Implementors must be stateless and safe for use by many threads, as every adapter.
 *
 * @param <OUTPUT> the output type of the action
 */
public interface AsyncSagaAdapter<OUTPUT> extends SagaAdapter<OUTPUT> {

    /**
     * Starts the action.
     *
     * @param sagaNode        the node for which this action is invoked
     * @param sagaInput       the original request data associated with this saga
     * @param dependeesOutput the output from the execution of the nodes this node depends on
     * @return a stage that completes with the action's output, or exceptionally with its failure; not null
     * @throws AbortSagaException to roll the saga back; throw a new instance each time
     */
    CompletionStage<OUTPUT> executeActionAsync(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) throws AbortSagaException;

    /**
     * Starts the compensating action. Called during rollback only if {@link #hasCompensatingAction()} is true, with the
     * same rules as {@link SagaAdapter#executeCompensatingAction}: for a node whose action ended (with its logged output)
     * or started but never ended (with a {@code null} output); it must be safe to call more than once.
     *
     * @param sagaNode     the node for which the compensating action is invoked
     * @param sagaInput    the original request data associated with this saga
     * @param actionOutput the output of the action, as logged, or {@code null}
     * @return a stage that completes when the compensation is done, or exceptionally with its failure; not null
     */
    CompletionStage<Void> executeCompensatingActionAsync(SagaNode sagaNode, Object sagaInput, OUTPUT actionOutput);

    /**
     * Not used: the engine calls {@link #executeActionAsync} for an asynchronous adapter.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default OUTPUT executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) throws AbortSagaException {
        throw new UnsupportedOperationException("An asynchronous adapter is run with executeActionAsync: " + name());
    }

    /**
     * Not used: the engine calls {@link #executeCompensatingActionAsync} for an asynchronous adapter.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void executeCompensatingAction(SagaNode sagaNode, Object sagaInput, OUTPUT actionOutput) {
        throw new UnsupportedOperationException("An asynchronous adapter is run with executeCompensatingActionAsync: " + name());
    }
}
