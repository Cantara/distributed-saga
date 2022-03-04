module no.cantara.saga.execution {

    requires java.base;
    requires no.cantara.saga.api;
    requires no.cantara.concurrent.futureselector;
    requires no.cantara.sagalog;

    exports no.cantara.saga.execution;
    exports no.cantara.saga.execution.adapter;

    uses no.cantara.sagalog.SagaLogInitializer;
}
