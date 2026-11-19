package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogOwner;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.SQLException;
import java.util.List;

import static no.cantara.sagalog.postgres.TwoProcesses.execute;
import static no.cantara.sagalog.postgres.TwoProcesses.process;
import static no.cantara.sagalog.postgres.TwoProcesses.query;
import static no.cantara.sagalog.postgres.TwoProcesses.unwrap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * What a caller gets when an operation fails (sp-7), the index for reads by execution (sp-10), and what shutdown
 * leaves (sp-11).
 */
@Test(groups = "integration")
public class PostgresSagaLogErrorsTest {

    private static final String SCHEMA = "sagalog_errors";

    @BeforeMethod
    public void cleanSchema() throws SQLException {
        TwoProcesses.createSchema(SCHEMA);
    }

    @Test
    public void thatAClosedLogSaysSoAndNamesTheLog() {
        PostgresSagaLogPool pool = process(SCHEMA, "01", 2);
        try {
            SagaLogId id = pool.idFor("01", "closed-log");
            SagaLog log = pool.connect(id);
            pool.remove(id);
            for (Runnable operation : List.<Runnable>of(
                    () -> log.write(log.builder().startSaga("e", "s", "{}")).join(),
                    () -> log.truncate().join(),
                    () -> log.readIncompleteSagas().count(),
                    () -> log.readEntries("e").count())) {
                IllegalStateException e = expectThrows(IllegalStateException.class, operation::run);
                assertTrue(e.getMessage().contains("closed-log") && e.getMessage().contains("closed"), e.getMessage());
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatAFailedStatementNamesTheOperationAndTheLogAndKeepsTheCause() throws SQLException {
        PostgresSagaLogPool pool = process(SCHEMA, "01", 2);
        try {
            SagaLog log = pool.connect(pool.idFor("01", "gone"));
            execute("DROP TABLE \"" + SCHEMA + "\".\"SAGALOG_ns_01_gone\"");
            Throwable e = unwrap(expectThrows(RuntimeException.class, () -> log.write(log.builder().startSaga("e", "s", "{}")).join()));
            assertEquals(e.getClass().getSimpleName(), "PostgresSagaLogException", String.valueOf(e));
            assertTrue(e.getMessage().contains("write") && e.getMessage().contains("gone"), e.getMessage());
            assertTrue(e.getCause() instanceof SQLException, String.valueOf(e.getCause()));
            assertTrue(e.getMessage().contains("42P01"), "the SQLState: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatAnExhaustedPoolNamesTheLog() {
        PostgresSagaLogPool pool = process(SCHEMA, "01", 1);
        try {
            pool.connect(pool.idFor("01", "first"));
            Throwable e = expectThrows(RuntimeException.class, () -> pool.connect(pool.idFor("01", "second")));
            assertEquals(e.getClass().getSimpleName(), "PostgresSagaLogException", String.valueOf(e));
            assertTrue(e.getMessage().contains("second") && e.getMessage().contains("connection-pool.max-size"), e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatReadsByExecutionUseAnIndexAlsoOnATableFromBefore() throws SQLException {
        execute("CREATE TABLE \"" + SCHEMA + "\".\"SAGALOG_ns_01_indexed\" (entry_id uuid NOT NULL, txid varchar NOT NULL,"
                + " entry_type smallint NOT NULL, node_id varchar NOT NULL, saga_name varchar NULL, data text NULL,"
                + " PRIMARY KEY (entry_id, txid))");
        PostgresSagaLogPool pool = process(SCHEMA, "01", 2);
        try {
            pool.connect(pool.idFor("01", "indexed"));
            pool.connect(pool.idFor("01", "fresh"));
            for (String table : List.of("SAGALOG_ns_01_indexed", "SAGALOG_ns_01_fresh")) {
                List<String> indexes = query("SELECT indexdef FROM pg_indexes WHERE schemaname = '" + SCHEMA + "' AND tablename = '" + table + "'");
                assertTrue(indexes.stream().anyMatch(def -> def.contains("(txid, entry_id)")), table + ": " + indexes);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void thatShutdownLeavesNoOwnershipsOrOpenLogs() {
        PostgresSagaLogPool pool = process(SCHEMA, "01", 2);
        SagaLogId id = pool.registerInstanceLocalIdFor("owned");
        SagaLog log = pool.tryTakeOwnership(new SagaLogOwner("o"), id);
        assertEquals(pool.instanceLocalSagaLogOwnerships().size(), 1);
        pool.shutdown();
        assertEquals(pool.instanceLocalSagaLogOwnerships().size(), 0, "ownerships after shutdown");
        expectThrows(IllegalStateException.class, () -> log.readIncompleteSagas().count());
    }
}
