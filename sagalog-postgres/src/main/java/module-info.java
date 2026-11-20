import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.postgres.PostgresSagaLogInitializer;

module no.cantara.sagalog.postgres {
    requires transitive no.cantara.sagalog;
    requires java.sql;
    requires com.zaxxer.hikari;
    requires org.postgresql.jdbc;
    requires org.slf4j;

    // PostgresSagaLogException, so a caller on the module path can catch it; the other public type is the initializer
    exports no.cantara.sagalog.postgres;

    provides SagaLogInitializer with PostgresSagaLogInitializer;
}
