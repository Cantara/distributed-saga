import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.file.FileSagaLogInitializer;

module no.cantara.sagalog.file {
    requires no.cantara.sagalog;

    opens no.cantara.sagalog.file;

    provides SagaLogInitializer with FileSagaLogInitializer;
}
