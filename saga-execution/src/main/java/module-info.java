module no.cantara.saga.execution {

    requires java.base;
    requires transitive no.cantara.saga.api;
    requires transitive no.cantara.concurrent.futureselector;
    requires transitive no.cantara.sagalog;

    exports no.cantara.saga.execution;
    exports no.cantara.saga.execution.adapter;

    uses no.cantara.sagalog.SagaLogInitializer;

}
