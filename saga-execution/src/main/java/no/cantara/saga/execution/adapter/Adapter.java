package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.SagaNode;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class Adapter<O> implements SagaAdapter<O> {

    protected final Class<O> outputClazz;
    protected final String name;
    private final BiFunction<Object, Map<SagaNode, Object>, O> action;
    private final BiConsumer<Object, O> compensatingConsumer;

    /**
     * An adapter without an action or a compensating action, for subclasses that override {@link #executeAction}
     * and, if they compensate, {@link #executeCompensatingAction}.
     */
    public Adapter(Class<O> outputClazz, String name) {
        this(outputClazz, name, (sagaInput, dependeesOutput) -> null, null);
    }

    /**
     * An adapter with an action and no compensating action.
     */
    public Adapter(Class<O> outputClazz, String name, BiFunction<Object, Map<SagaNode, Object>, O> action) {
        this(outputClazz, name, action, null);
    }

    /**
     * An adapter with an action and a compensating action; a {@code null} compensating action means none.
     */
    public Adapter(Class<O> outputClazz, String name, BiFunction<Object, Map<SagaNode, Object>, O> action, BiConsumer<Object, O> compensatingConsumer) {
        this.outputClazz = outputClazz;
        this.name = name;
        this.action = action;
        this.compensatingConsumer = compensatingConsumer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public O executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        return action.apply(sagaInput, dependeesOutput);
    }

    @Override
    public void executeCompensatingAction(SagaNode sagaNode, Object sagaInput, O actionOutput) {
        if (compensatingConsumer != null) {
            compensatingConsumer.accept(sagaInput, actionOutput);
        }
    }

    /**
     * True when a compensating action was given to the constructor, or when a subclass overrides
     * {@link #executeCompensatingAction}, so an override is never skipped by mistake.
     */
    @Override
    public boolean hasCompensatingAction() {
        if (compensatingConsumer != null) {
            return true;
        }
        try {
            return getClass().getMethod("executeCompensatingAction", SagaNode.class, Object.class, Object.class)
                    .getDeclaringClass() != Adapter.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ActionOutputSerializer<O> serializer() {
        return new ActionOutputSerializerToStringAndStringConstructor<>(outputClazz);
    }
}
