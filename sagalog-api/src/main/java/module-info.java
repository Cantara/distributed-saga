import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.memory.MemorySagaLogInitializer;

module no.cantara.sagalog {
    requires java.base;

    exports no.cantara.sagalog;

    provides SagaLogInitializer with MemorySagaLogInitializer;
}
