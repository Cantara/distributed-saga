import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.memory.MemorySagaLogInitializer;

module no.cantara.sagalog {
    requires java.base;

    exports no.cantara.sagalog;

    opens no.cantara.sagalog.memory;

    provides SagaLogInitializer with MemorySagaLogInitializer;
}
