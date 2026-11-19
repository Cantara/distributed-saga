package no.cantara.sagalog.postgres;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Saga-log entry ids: UUIDv7 (RFC 9562), laid out as PostgreSQL 18's {@code uuidv7()} lays them out, the same layout
 * as the message ids of rawdata-client used by Linked Data Store.
 * <pre>
 *  48 bits  unix_ts_ms   milliseconds since 1970-01-01T00:00Z
 *   4 bits  version      7
 *  12 bits  rand_a       sub-millisecond fraction (RFC 9562 §6.2, method 3)
 *   2 bits  variant      0b10
 *  62 bits  rand_b       random
 * </pre>
 * Ids sort by time in the order of {@link #ORDER}, which compares the 16 bytes as unsigned numbers, as PostgreSQL
 * compares {@code uuid} values. {@link UUID#compareTo} compares signed longs and must not be used to order ids.
 */
final class UuidV7 {

    static final Comparator<UUID> ORDER = (a, b) -> {
        int most = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return most == 0 ? Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits()) : most;
    };

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT = 0x8000_0000_0000_0000L;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long MAX_TIMESTAMP = 0xFFFF_FFFF_FFFFL;

    private UuidV7() {
    }

    /**
     * Generates an id for now.
     */
    static UUID generate() {
        Instant now = Instant.now();
        long millis = now.toEpochMilli();
        long fraction = ((now.getNano() % 1_000_000L) * 4096L) / 1_000_000L; // 12 bits of the millisecond
        long randB = ThreadLocalRandom.current().nextLong() & RAND_B_MASK;
        if (fraction == 0 && randB == 0) {
            randB = 1;
        }
        return new UUID((millis << 16) | VERSION_7 | fraction, VARIANT | randB);
    }

    /**
     * Generates an id greater than the previous one: a new id when the clock is ahead of the previous id, otherwise
     * the previous id plus one. Ids stay strictly increasing within one millisecond and when the clock moves back.
     *
     * @throws IllegalStateException if the previous id is the greatest UUIDv7 there is
     */
    static UUID next(UUID previous) {
        UUID generated = generate();
        return ORDER.compare(generated, previous) > 0 ? generated : increment(previous);
    }

    private static UUID increment(UUID id) {
        long timestamp = id.getMostSignificantBits() >>> 16;
        long randA = id.getMostSignificantBits() & RAND_A_MASK;
        long randB = id.getLeastSignificantBits() & RAND_B_MASK;
        if (randB < RAND_B_MASK) {
            randB++;
        } else {
            randB = 0;
            if (randA < RAND_A_MASK) {
                randA++;
            } else {
                randA = 0;
                if (timestamp == MAX_TIMESTAMP) {
                    throw new IllegalStateException("No UUIDv7 is greater than " + id);
                }
                timestamp++;
            }
        }
        return new UUID((timestamp << 16) | VERSION_7 | randA, VARIANT | randB);
    }

    static byte[] toBytes(UUID id) {
        byte[] bytes = new byte[16];
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - 8 * i));
            bytes[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return bytes;
    }

    static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("An id is 16 bytes");
        }
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
            lsb = (lsb << 8) | (bytes[8 + i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
