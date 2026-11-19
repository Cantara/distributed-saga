import no.cantara.sagalog.SagaLogInitializer;

module no.cantara.saga.samples.polyglot {
    requires no.cantara.saga.api;
    requires no.cantara.saga.execution;
    requires no.cantara.saga.serialization;
    requires no.cantara.concurrent.futureselector;
    requires java.base;
    requires undertow.core;
    requires org.json;
    requires no.cantara.sagalog;
    requires no.cantara.sagalog.file;

    uses SagaLogInitializer;
}
