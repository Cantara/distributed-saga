package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.SagaNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AdapterLoader {

    final Map<String, SagaAdapter> adapterByName = new ConcurrentHashMap<>();

    public AdapterLoader() {
        register(new SagaAdapterStart());
        register(new SagaAdapterEnd());
    }

    public AdapterLoader register(SagaAdapter sagaAdapter) {
        adapterByName.put(sagaAdapter.name(), sagaAdapter);
        return this;
    }

    public SagaAdapter load(SagaNode node) {
        SagaAdapter sagaAdapter = adapterByName.get(node.adapter);
        if (sagaAdapter == null) {
            throw new IllegalArgumentException("Unknown adapter: \"" + node.adapter + "\"");
        }
        return sagaAdapter;
    }
}
