package no.cantara.sagalog.file;

import org.testng.annotations.Test;

import java.nio.file.Paths;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

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

    /**
     * sf-13: a log name or instance id that is not a plain file-name part is refused.
     */
    @Test
    public void thatPathSeparatorsAndTheSeparatorAreRefused() {
        for (String name : new String[]{"a/b", "../x", "a\\b", "a\u0000b"}) {
            expectThrows(IllegalArgumentException.class, () -> new FileSagaLogId(Paths.get("."), "01", name));
            expectThrows(IllegalArgumentException.class, () -> new FileSagaLogId(Paths.get("."), name, "log"));
        }
        expectThrows(IllegalArgumentException.class, () -> new FileSagaLogId(Paths.get("."), "a.--.b", "log"));
        expectThrows(IllegalArgumentException.class, () -> new FileSagaLogId(Paths.get("."), "a.--", "log"));
        // a separator in the log name parses back
        FileSagaLogId id = new FileSagaLogId(Paths.get("."), "01", "x.--.y");
        assertEquals(new FileSagaLogId(id.getPath()), id);
    }
}
