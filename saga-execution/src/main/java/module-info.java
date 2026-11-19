module no.cantara.saga.execution {

    requires java.base;
    requires transitive no.cantara.saga.api;
    requires transitive no.cantara.concurrent.futureselector;
    requires transitive no.cantara.sagalog;

    exports no.cantara.saga.execution;
    exports no.cantara.saga.execution.adapter;

    // main code loads no provider; the tests, patched into this module, load the memory saga log through
    // ServiceLoader, which needs the declaration
    uses no.cantara.sagalog.SagaLogInitializer;

}
