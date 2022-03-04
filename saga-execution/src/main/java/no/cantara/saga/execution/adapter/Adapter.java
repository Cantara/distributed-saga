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

    public Adapter(Class<O> outputClazz, String name) {
        this(outputClazz, name, (sagaInput, dependeesOutput) -> null, (sagaInput, dependeesOutput) -> {
        });
    }

    public Adapter(Class<O> outputClazz, String name, BiFunction<Object, Map<SagaNode, Object>, O> action) {
        this(outputClazz, name, action, (sagaInput, dependeesOutput) -> {
        });
    }

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
        compensatingConsumer.accept(sagaInput, actionOutput);
    }

    @Override
    public ActionOutputSerializer<O> serializer() {
        return new ActionOutputSerializerToStringAndStringConstructor<>(outputClazz);
    }
}
