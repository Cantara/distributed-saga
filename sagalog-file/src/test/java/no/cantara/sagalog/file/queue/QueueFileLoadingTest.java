// Copyright 2012 Square, Inc.
//
// Ported from Tape 2.0.0-beta1 (https://github.com/square/tape, tag 2.0.0-beta1,
// tape/src/test/java/com/squareup/tape2/QueueFileLoadingTest.java and QueueTestUtils.java), licensed under the
// Apache License, Version 2.0. Changes for distributed-saga: package; JUnit 4 replaced by TestNG; Okio replaced by
// java.nio; the fixtures read from /queue/; testAddWithReadOnlyFileMissesMonitor left out, because it tests Tape's
// FileObjectQueue, which was not copied. See NOTICE at the root of the repository.
package no.cantara.sagalog.file.queue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public final class QueueFileLoadingTest {

    static final String TRUNCATED_ONE_ENTRY_SERIALIZED_QUEUE = "/queue/truncated-one-entry-serialized-queue";
    static final String TRUNCATED_EMPTY_SERIALIZED_QUEUE = "/queue/truncated-empty-serialized-queue";
    static final String ONE_ENTRY_SERIALIZED_QUEUE = "/queue/one-entry-serialized-queue";
    static final String EMPTY_SERIALIZED_QUEUE = "/queue/empty-serialized-queue";
    static final String FRESH_SERIALIZED_QUEUE = "fresh-serialized-queue";

    private File testFile;

    static File copyTestFile(String file) throws IOException {
        File newFile = File.createTempFile(file.substring(file.lastIndexOf('/') + 1), "test");
        try (InputStream in = QueueFileLoadingTest.class.getResourceAsStream(file)) {
            Files.copy(in, newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(newFile.exists());
        return newFile;
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        assertTrue(testFile.delete(), "Failed to delete test file " + testFile.getPath());
    }

    @Test
    public void testMissingFileInitializes() throws Exception {
        testFile = File.createTempFile(FRESH_SERIALIZED_QUEUE, "test");
        assertTrue(testFile.delete());
        assertFalse(testFile.exists());
        QueueFile queue = new QueueFile.Builder(testFile).build();
        assertEquals(queue.size(), 0);
        assertTrue(testFile.exists());
        queue.close();
    }

    @Test
    public void testEmptyFileInitializes() throws Exception {
        testFile = copyTestFile(EMPTY_SERIALIZED_QUEUE);
        QueueFile queue = new QueueFile.Builder(testFile).build();
        assertEquals(queue.size(), 0);
        queue.close();
    }

    @Test
    public void testSingleEntryFileInitializes() throws Exception {
        testFile = copyTestFile(ONE_ENTRY_SERIALIZED_QUEUE);
        QueueFile queue = new QueueFile.Builder(testFile).build();
        assertEquals(queue.size(), 1);
        queue.close();
    }

    @Test(expectedExceptions = IOException.class)
    public void testTruncatedEmptyFileThrows() throws Exception {
        testFile = copyTestFile(TRUNCATED_EMPTY_SERIALIZED_QUEUE);
        new QueueFile.Builder(testFile).build();
    }

    @Test(expectedExceptions = IOException.class)
    public void testTruncatedOneEntryFileThrows() throws Exception {
        testFile = copyTestFile(TRUNCATED_ONE_ENTRY_SERIALIZED_QUEUE);
        new QueueFile.Builder(testFile).build();
    }

    @Test(expectedExceptions = IOException.class)
    public void testCreateWithReadOnlyFileThrowsException() throws Exception {
        testFile = copyTestFile(TRUNCATED_ONE_ENTRY_SERIALIZED_QUEUE);
        assertTrue(testFile.setWritable(false));

        // Should throw an exception.
        new QueueFile.Builder(testFile).build();
    }
}
