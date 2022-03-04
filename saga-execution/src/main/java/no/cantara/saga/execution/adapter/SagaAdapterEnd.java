package no.cantara.saga.execution.adapter;

import no.cantara.saga.api.Saga;

class SagaAdapterEnd extends Adapter<Object> {

    public SagaAdapterEnd() {
        super(Object.class, Saga.ADAPTER_END);
    }
}
