package no.cantara.saga.execution;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import no.cantara.sagalog.SagaLogPool;

import java.util.Map;

/**
 * The fault matrix on the PostgreSQL saga log, through its production initializer (HikariCP) on an embedded server.
 */
public class PostgresSagaFaultMatrixTest extends SagaFaultMatrixTest {

    EmbeddedPostgres postgres;

    @Override
    protected SagaLogPool createLogPool() throws Exception {
        postgres = EmbeddedPostgres.start();
        return initialize("no.cantara.sagalog.postgres.PostgresSagaLogInitializer", Map.of(
                "cluster.owner", "faultmatrix",
                "cluster.name", "fm",
                "postgres.driver.port", String.valueOf(postgres.getPort()),
                "postgres.driver.user", "postgres",
                "postgres.driver.password", "postgres",
                "postgres.driver.database", "postgres"));
    }

    @Override
    protected void closeProvider() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }
}
