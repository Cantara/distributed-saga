import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.postgres.PostgresSagaLogInitializer;

module no.cantara.sagalog.postgres {
    requires no.cantara.sagalog;
    requires java.logging;
    requires java.sql;
    requires de.huxhorn.sulky.ulid;
    requires com.zaxxer.hikari;
    requires org.postgresql.jdbc;
    requires org.slf4j;

    opens no.cantara.sagalog.postgres;
    opens no.cantara.sagalog.postgres.init;

    provides SagaLogInitializer with PostgresSagaLogInitializer;
}
