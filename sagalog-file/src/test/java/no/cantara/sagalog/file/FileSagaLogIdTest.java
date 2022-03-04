package no.cantara.sagalog.file;

import org.testng.annotations.Test;

import java.nio.file.Paths;

import static org.testng.Assert.assertEquals;

public class FileSagaLogIdTest {

    @Test
    public void thatGetSimpleLogNameWorks() {
        FileSagaLogId logIdFromParts = new FileSagaLogId(Paths.get("."), "01", "hi");
        FileSagaLogId logIdFromPath = new FileSagaLogId(logIdFromParts.getPath());
        assertEquals(logIdFromPath, logIdFromParts);
    }

    @Test
    public void thatGetAdvancedLogNameWorks() {
        FileSagaLogId logIdFromParts = new FileSagaLogId(Paths.get("."), "01", "hola-.:$there");
        FileSagaLogId logIdFromPath = new FileSagaLogId(logIdFromParts.getPath());
        assertEquals(logIdFromPath, logIdFromParts);
    }

}
