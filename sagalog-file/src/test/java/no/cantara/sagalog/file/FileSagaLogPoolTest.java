package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;

public class FileSagaLogPoolTest {

    FileSagaLogPool pool;

    @BeforeMethod
    public void setup() throws IOException {
        FileSagaLogInitializer initializer = new FileSagaLogInitializer();
        this.pool = initializer.initialize(Map.of("filesagalog.folder", "target/sagalog-pool-test", "cluster.instance-id", "test01"));

        // delete all saga-logs
        Files.list(Paths.get("target/sagalog-pool-test")).filter(path -> path.toFile().getName().endsWith(".sagalog")).forEach(path -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @AfterMethod
    public void teardown() {
        pool.shutdown();
    }

    @Test
    public void thatIdForHasCorrectHashcodeEquals() {
        assertEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "somelogid"));
        assertFalse(pool.idFor("A", "somelogid") == pool.idFor("A", "somelogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "otherlogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("B", "otherlogid"));
    }

    @Test
    void thatClusterWideLogIdsAreTheSameAsInstanceLocalLogIds() {
        SagaLogId l1 = pool.registerInstanceLocalIdFor("l1");
        pool.connect(l1);
        SagaLogId l2 = pool.registerInstanceLocalIdFor("l2");
        pool.connect(l2);
        SagaLogId x1 = pool.idFor("otherInstance", "x1");
        pool.connect(x1);
        assertEquals(pool.clusterWideLogIds(), Set.of(l1, l2, x1));
    }

    @Test
    void thatConnectExternalProducesANonNullSagaLog() {
        assertNotNull(pool.connectExternal(pool.registerInstanceLocalIdFor("anyId")));
    }
}
