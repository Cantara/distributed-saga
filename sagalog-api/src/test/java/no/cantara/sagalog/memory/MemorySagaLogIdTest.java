package no.cantara.sagalog.memory;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class MemorySagaLogIdTest {

    @Test
    public void thatGetSimpleLogNameWorks() {
        assertEquals(new MemorySagaLogId("01", "hi").getLogName(), "hi");
    }

    @Test
    public void thatGetAdvancedLogNameWorks() {
        assertEquals(new MemorySagaLogId("01", "hola-.:$there").getLogName(), "hola-.:$there");
    }

    @Test
    public void thatANullClusterInstanceIdIsRefused() {
        expectThrows(NullPointerException.class, () -> new MemorySagaLogId(null, "hi"));
    }

    @Test
    public void thatANullLogNameIsRefused() {
        expectThrows(IllegalArgumentException.class, () -> new MemorySagaLogId("01", null));
    }
}
