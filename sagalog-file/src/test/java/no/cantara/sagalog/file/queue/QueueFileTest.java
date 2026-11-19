package no.cantara.sagalog.file.queue;

import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Checks that the copy of Tape's QueueFile reads and writes the same file format as Tape 2.0.0-beta1.
 * The fixture tape-2.0.0-beta1.queue was written by the Tape 2.0.0-beta1 jar with the sequence in {@link #writeSequence}.
 */
public class QueueFileTest {

    private static final String FIXTURE = "/queue/tape-2.0.0-beta1.queue";

    static byte[] entry(int i) {
        byte[] b = new byte[(i * 37) % 300 + 1];
        for (int j = 0; j < b.length; j++) {
            b[j] = (byte) (i + j);
        }
        return b;
    }

    static void writeSequence(QueueFile q) throws IOException {
        for (int i = 0; i < 60; i++) {
            q.add(entry(i));
        }
        q.remove(25);
        for (int i = 60; i < 90; i++) {
            q.add(entry(i));
        }
        q.remove(10);
    }

    static List<byte[]> expectedEntries() {
        List<byte[]> expected = new ArrayList<>();
        for (int i = 35; i < 90; i++) {
            expected.add(entry(i));
        }
        return expected;
    }

    private Path copyFixture() throws IOException {
        Path file = Files.createTempFile("tape-fixture", ".queue");
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(FIXTURE))) {
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static void assertEntries(QueueFile q, List<byte[]> expected) {
        List<byte[]> actual = new ArrayList<>();
        for (byte[] bytes : q) {
            actual.add(bytes);
        }
        assertEquals(actual.size(), expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(actual.get(i), expected.get(i), "entry " + i);
        }
        assertEquals(q.size(), expected.size());
    }

    @Test
    public void thatAFileWrittenByTapeIsRead() throws IOException {
        Path file = copyFixture();
        try (QueueFile q = new QueueFile.Builder(file.toFile()).build()) {
            assertEntries(q, expectedEntries());
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void thatTheSameOperationsWriteTheSameBytesAsTape() throws IOException {
        Path file = Files.createTempFile("tape-copy", ".queue");
        Files.delete(file);
        try {
            try (QueueFile q = new QueueFile.Builder(file.toFile()).build()) {
                writeSequence(q);
            }
            byte[] fixture;
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(FIXTURE))) {
                fixture = in.readAllBytes();
            }
            assertEquals(Files.readAllBytes(file), fixture);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void thatAFileWrittenByTapeCanBeAppendedRemovedAndClearedAndReopened() throws IOException {
        Path file = copyFixture();
        try {
            List<byte[]> expected = expectedEntries();
            try (QueueFile q = new QueueFile.Builder(file.toFile()).build()) {
                q.add(entry(90));
                expected.add(entry(90));
                q.remove(5);
                expected.subList(0, 5).clear();
                assertEquals(q.peek(), expected.get(0));
            }
            try (QueueFile q = new QueueFile.Builder(file.toFile()).build()) {
                assertEntries(q, expected);
                q.clear();
                assertTrue(q.isEmpty());
                assertNull(q.peek());
            }
            try (QueueFile q = new QueueFile.Builder(file.toFile()).build()) {
                assertTrue(q.isEmpty());
            }
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void thatANewFileIsCreated() throws IOException {
        File file = Files.createTempFile("tape-new", ".queue").toFile();
        assertTrue(file.delete());
        try (QueueFile q = new QueueFile.Builder(file).build()) {
            assertTrue(q.isEmpty());
            q.add(new byte[]{1, 2, 3});
            assertEquals(q.peek(), new byte[]{1, 2, 3});
        } finally {
            assertTrue(file.delete());
        }
    }
}
