package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class FileSagaLogPoolTest {

    FileSagaLogPool pool;

    @BeforeMethod
    public void setup() throws IOException {
        FileSagaLogInitializer initializer = new FileSagaLogInitializer();
        this.pool = (FileSagaLogPool) initializer.initialize(Map.of("filesagalog.folder", "target/sagalog-pool-test", "cluster.instance-id", "test01"));

        // delete all saga-logs
        Files.list(Paths.get("target/sagalog-pool-test")).filter(path -> path.toFile().getName().endsWith(".sagalog")).forEach(path -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @AfterMethod
    public void teardown() {
        pool.shutdown();
    }

    @Test
    public void thatIdForHasCorrectHashcodeEquals() {
        assertEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "somelogid"));
        assertFalse(pool.idFor("A", "somelogid") == pool.idFor("A", "somelogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("A", "otherlogid"));
        assertNotEquals(pool.idFor("A", "somelogid"), pool.idFor("B", "otherlogid"));
    }

    @Test
    void thatClusterWideLogIdsAreTheSameAsInstanceLocalLogIds() {
        SagaLogId l1 = pool.registerInstanceLocalIdFor("l1");
        pool.connect(l1);
        SagaLogId l2 = pool.registerInstanceLocalIdFor("l2");
        pool.connect(l2);
        SagaLogId x1 = pool.idFor("otherInstance", "x1");
        pool.connect(x1);
        assertEquals(pool.clusterWideLogIds(), Set.of(l1, l2, x1));
    }

    @Test
    void thatConnectExternalProducesANonNullSagaLog() throws Exception {
        SagaLog log = pool.connectExternal(pool.registerInstanceLocalIdFor("anyId"));
        assertNotNull(log);
        log.close(); // not in the pool: shutdown would not release its lock
    }

    /**
     * sf-2: listing the folder must not leak a directory handle.
     */
    @Test
    void thatClusterWideLogIdsDoesNotLeakFileDescriptors() throws IOException {
        pool.connect(pool.registerInstanceLocalIdFor("l1"));
        pool.clusterWideLogIds();
        long before = openFileDescriptors();
        for (int i = 0; i < 200; i++) {
            pool.clusterWideLogIds();
        }
        long after = openFileDescriptors();
        assertTrue(after - before < 20, "open file descriptors grew from " + before + " to " + after);
    }

    static long openFileDescriptors() throws IOException {
        Path fds = Paths.get("/proc/self/fd");
        if (!Files.isDirectory(fds)) {
            fds = Paths.get("/dev/fd");
        }
        if (!Files.isDirectory(fds)) {
            throw new SkipException("cannot count open file descriptors here");
        }
        try (Stream<Path> list = Files.list(fds)) {
            return list.count();
        }
    }

    /**
     * sf-8: a stray file that is not a saga-log is skipped.
     */
    @Test
    void thatAStrayFileIsSkipped() throws IOException {
        SagaLogId l1 = pool.registerInstanceLocalIdFor("l1");
        pool.connect(l1);
        Path stray = Paths.get("target/sagalog-pool-test/backup.sagalog");
        Files.write(stray, new byte[0]);
        try {
            assertEquals(pool.clusterWideLogIds(), Set.of(l1));
        } finally {
            Files.delete(stray);
        }
    }
}
