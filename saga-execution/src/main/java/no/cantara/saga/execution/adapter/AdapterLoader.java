package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.SagaNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapters of a saga execution, by name. The start and end adapters are registered by the constructor.
 * Register every adapter before sagas start: a node whose adapter is unknown fails only when it is reached.
 * Safe for use by many threads.
 */
public class AdapterLoader {

    final Map<String, SagaAdapter<?>> adapterByName = new ConcurrentHashMap<>();

    public AdapterLoader() {
        SagaAdapter<?> start = new SagaAdapterStart();
        SagaAdapter<?> end = new SagaAdapterEnd();
        adapterByName.put(start.name(), start);
        adapterByName.put(end.name(), end);
    }

    /**
     * Registers an adapter under its {@link SagaAdapter#name()}, replacing one of the same name.
     *
     * @return this loader
     */
    public AdapterLoader register(SagaAdapter<?> sagaAdapter) {
        adapterByName.put(sagaAdapter.name(), sagaAdapter);
        return this;
    }

    /**
     * @return the adapter that the node names
     * @throws IllegalArgumentException if no adapter of that name is registered
     */
    public SagaAdapter<?> load(SagaNode node) {
        SagaAdapter<?> sagaAdapter = adapterByName.get(node.adapter);
        if (sagaAdapter == null) {
            throw new IllegalArgumentException("Unknown adapter: \"" + node.adapter + "\"");
        }
        return sagaAdapter;
    }
}
