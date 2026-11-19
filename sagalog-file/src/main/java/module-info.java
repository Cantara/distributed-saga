import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.file.FileSagaLogInitializer;

module no.cantara.sagalog.file {
    requires no.cantara.sagalog;

    provides SagaLogInitializer with FileSagaLogInitializer;
}
