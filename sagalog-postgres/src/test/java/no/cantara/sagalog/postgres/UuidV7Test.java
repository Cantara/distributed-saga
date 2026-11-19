package no.cantara.sagalog.postgres;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class UuidV7Test {

    @Test
    public void thatGeneratedIdsAreVersion7WithRfcVariant() {
        UUID id = UuidV7.generate();
        assertEquals(id.version(), 7);
        assertEquals(id.variant(), 2);
        long millis = id.getMostSignificantBits() >>> 16;
        assertTrue(Math.abs(System.currentTimeMillis() - millis) < 10_000);
    }

    @Test
    public void thatNextIsStrictlyIncreasing() {
        UUID previous = UuidV7.generate();
        for (int i = 0; i < 100_000; i++) {
            UUID next = UuidV7.next(previous);
            assertTrue(UuidV7.ORDER.compare(next, previous) > 0, previous + " -> " + next);
            assertEquals(next.version(), 7);
            assertEquals(next.variant(), 2);
            previous = next;
        }
    }

    @Test
    public void thatNextStaysAheadWhenThePreviousIdIsInTheFuture() {
        long future = System.currentTimeMillis() + 3_600_000L;
        UUID previous = new UUID((future << 16) | 0x7000L | 0x0FFFL, 0x8000_0000_0000_0000L | 0x3FFF_FFFF_FFFF_FFFFL);
        UUID next = UuidV7.next(previous);
        assertTrue(UuidV7.ORDER.compare(next, previous) > 0);
        assertEquals(next.getMostSignificantBits() >>> 16, future + 1);
        assertEquals(next.version(), 7);
    }

    @Test
    public void thatNextOfTheGreatestIdThrows() {
        UUID greatest = new UUID(0xFFFF_FFFF_FFFF_7FFFL, 0xBFFF_FFFF_FFFF_FFFFL);
        assertThrows(IllegalStateException.class, () -> UuidV7.next(greatest));
    }

    @Test
    public void thatOrderIsUnsigned() {
        UUID low = new UUID(0x0000_0000_0000_7000L, 0x8000_0000_0000_0000L);
        UUID high = new UUID(0x8000_0000_0000_7000L, 0x8000_0000_0000_0000L);
        assertTrue(UuidV7.ORDER.compare(low, high) < 0);
        assertTrue(low.compareTo(high) > 0); // why UUID.compareTo must not be used
    }

    @Test
    public void thatBytesRoundTripBigEndian() {
        UUID id = UuidV7.generate();
        byte[] bytes = UuidV7.toBytes(id);
        assertEquals(bytes.length, 16);
        assertEquals(UuidV7.fromBytes(bytes), id);
        assertEquals(UuidV7.toBytes(UUID.fromString("01234567-89ab-7def-8123-456789abcdef")),
                new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, 0x7d, (byte) 0xef,
                        (byte) 0x81, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef});
        assertThrows(IllegalArgumentException.class, () -> UuidV7.fromBytes(Arrays.copyOf(bytes, 15)));
    }
}
