package no.cantara.sagalog.memory;

import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;

public class MemorySagaLogPoolTest {

    MemorySagaLogPool pool;

    @BeforeMethod
    public void setup() {
        this.pool = new MemorySagaLogPool("PoolTestInstance01");
    }

    @AfterMethod
    public void teardown() {
        pool.shutdown();
    }

    @Test
    public void thatIdForHasCorrectHashcodeEquals() {
        assertEquals(pool.idFor("", "somelogid"), pool.idFor("", "somelogid"));
        assertFalse(pool.idFor("", "somelogid") == pool.idFor("", "somelogid"));
        assertNotEquals(pool.idFor("", "somelogid"), pool.idFor("", "otherlogid"));
    }

    @Test
    void thatClusterWideLogIdsAreTheSameAsInstanceLocalLogIds() {
        SagaLogId l1 = pool.registerInstanceLocalIdFor("1");
        SagaLogId l2 = pool.registerInstanceLocalIdFor("2");
        pool.idFor("otherInstance", "x");
        assertEquals(pool.clusterWideLogIds(), Set.of(l1, l2));
    }

    @Test
    void thatConnectExternalProducesANonNullSagaLog() {
        assertNotNull(pool.connectExternal(pool.registerInstanceLocalIdFor("anyId")));
    }
}
