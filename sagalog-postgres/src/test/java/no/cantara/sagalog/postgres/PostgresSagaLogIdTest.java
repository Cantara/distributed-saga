package no.cantara.sagalog.postgres;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class PostgresSagaLogIdTest {

    @Test
    public void thatGetSimpleLogNameWorks() {
        PostgresSagaLogId logIdFromParts = new PostgresSagaLogId("mycompany", "internal-sagalog-integration-testing", "01", "hi");
        PostgresSagaLogId logIdFromPath = new PostgresSagaLogId(logIdFromParts.getSchema(), logIdFromParts.getTableName());
        assertEquals(logIdFromPath, logIdFromParts);
    }

    @Test
    public void thatGetAdvancedLogNameWorks() {
        PostgresSagaLogId logIdFromParts = new PostgresSagaLogId("mycompany", "internal-sagalog-integration-testing", "01", "hola-.:$there");
        PostgresSagaLogId logIdFromPath = new PostgresSagaLogId(logIdFromParts.getSchema(), logIdFromParts.getTableName());
        assertEquals(logIdFromPath, logIdFromParts);
    }
}
