package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.SagaNode;

import java.util.Map;

/**
 * Implementors of adapter must provide a stateless, re-usage adapter that
 * is safe for usage with multiple threads. This should be easy enough as
 * all needed context is passed as arguments to every method.
 *
 * @param <OUTPUT> the output type produced by the action and compensating action
 *                 execution methods. The generic type is used to ensure type-safety
 *                 between instances of this class and instances of corresponding
 *                 serialization classes.
 */
public interface SagaAdapter<OUTPUT> {

    /**
     * The adapter name as it should appear in the saga-log. Note that this name
     * will be used for serialization and de-serialization. This means that care
     * must be taken if the name is changed for an adapter that has already been
     * used for serialization or saga-log operations.
     *
     * @return the adapter name.
     */
    String name();

    /**
     * @param sagaNode        the sagaNode for which this action is invoked.
     * @param sagaInput       the original request data associated with this saga.
     * @param dependeesOutput the output from the execution of the dejpendees of the saga-node
     *                        represented by this adapter.
     * @return the output from executing the action.
     * @throws AbortSagaException action implementation may choose to throw an AbortSagaException
     *                            in order to trigger a full saga rollback.
     */
    OUTPUT executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) throws AbortSagaException;

    /**
     * Execute the compensating action that this adapter implicitly represents using sagaInput.
     * Upon returning normally, it will be assumed that the compensating action was successfully
     * executed.
     *
     * @param sagaNode     the sagaNode for which this compensating action is invoked.
     * @param sagaInput    the original request data associated with this saga.
     * @param actionOutput the output from executing the {@link #executeAction(SagaNode, Object, Map<SagaNode, Object>)} method.
     */
    void executeCompensatingAction(SagaNode sagaNode, Object sagaInput, OUTPUT actionOutput);

    /**
     * @return a serializer than can be used to serialize and de-serialize any object output
     * by one of the action execute methods.
     */
    ActionOutputSerializer<OUTPUT> serializer();
}
