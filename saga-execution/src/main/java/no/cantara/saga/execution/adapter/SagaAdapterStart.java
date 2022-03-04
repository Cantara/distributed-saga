package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.Saga;

class SagaAdapterStart extends Adapter<Object> {

    public SagaAdapterStart() {
        super(Object.class, Saga.ADAPTER_START);
    }
}
