package no.cantara.sagalog.memory;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class MemorySagaLogIdTest {

    @Test
    public void thatGetSimpleLogNameWorks() {
        assertEquals(new MemorySagaLogId("01", "hi").getLogName(), "hi");
    }

    @Test
    public void thatGetAdvancedLogNameWorks() {
        assertEquals(new MemorySagaLogId("01", "hola-.:$there").getLogName(), "hola-.:$there");
    }
}
