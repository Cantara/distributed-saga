package no.cantara.sagalog.postgres;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

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

    @Test
    public void thatALogNameMayHoldAnUnderscore() {
        PostgresSagaLogId id = new PostgresSagaLogId("my_company", "ns", "01", "a_b");
        assertEquals(new PostgresSagaLogId(id.getSchema(), id.getTableName()), id);
    }

    /**
     * sp-6: a double quote, or any character outside the documented set, is refused in every part of an id.
     */
    @Test
    public void thatAQuoteIsRefusedInEveryPart() {
        for (String bad : List.of("x\"y", "x y", "x;y", "x'y", "x\u0000y", "", "ø")) {
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId(bad, "ns", "01", "log"));
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", bad, "01", "log"));
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", "ns", bad, "log"));
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", "ns", "01", bad));
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId(bad, "SAGALOG_ns_01_log"));
            expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", "SAGALOG_ns_01_" + bad));
        }
        expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", "ns", "01",
                "x\" (a int); DROP TABLE victim; CREATE TABLE \"y"));
    }

    /**
     * sp-6: PostgreSQL truncates identifiers to 63 bytes, so a longer table name or schema would put two logs in one
     * table. They are refused.
     */
    @Test
    public void thatATableNameOver63BytesIsRefused() {
        String prefix = "SAGALOG_ns_01_";
        String fits = "a".repeat(63 - prefix.length());
        assertEquals(new PostgresSagaLogId("schema", "ns", "01", fits).getTableName().length(), 63);
        expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", "ns", "01", fits + "a"));
        expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("schema", prefix + fits + "a"));
        new PostgresSagaLogId("s".repeat(63), "ns", "01", "log");
        expectThrows(IllegalArgumentException.class, () -> new PostgresSagaLogId("s".repeat(64), "ns", "01", "log"));
    }

    @Test
    public void thatAnIdentifierIsQuotedWithItsQuotesDoubled() {
        assertEquals(PostgresSagaLogId.quote("plain"), "\"plain\"");
        assertEquals(PostgresSagaLogId.quote("a\"b"), "\"a\"\"b\"");
    }

    /**
     * sp-17: ids compare part by part, without overflow.
     */
    @Test
    public void thatIdsCompareWithoutOverflow() {
        PostgresSagaLogId a = new PostgresSagaLogId("A", "ns", "01", "log");
        PostgresSagaLogId z = new PostgresSagaLogId("z", "ns", "01", "log");
        assertTrue(a.compareTo(z) < 0);
        assertTrue(z.compareTo(a) > 0);
        assertTrue(new PostgresSagaLogId("s", "ns", "01", "a").compareTo(new PostgresSagaLogId("s", "ns", "02", "a")) < 0);
        assertEquals(a.compareTo(new PostgresSagaLogId("A", "ns", "01", "log")), 0);
    }
}
