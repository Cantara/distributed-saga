package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static no.cantara.sagalog.postgres.TwoProcesses.admin;
import static no.cantara.sagalog.postgres.TwoProcesses.awaitNoHolderOf;
import static no.cantara.sagalog.postgres.TwoProcesses.holdersOf;
import static no.cantara.sagalog.postgres.TwoProcesses.lockKey;
import static no.cantara.sagalog.postgres.TwoProcesses.loseSessionOf;
import static no.cantara.sagalog.postgres.TwoProcesses.process;
import static no.cantara.sagalog.postgres.TwoProcesses.query;
import static no.cantara.sagalog.postgres.TwoProcesses.unwrap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Faults inside the database, below the saga log (fm-3): a lost session ({@code pg_terminate_backend}), another
 * process (a second HikariCP pool) taking the log in between, a lost commit acknowledgement, a table locked by a third
 * session, and two processes opening a new log at once.
 */
@Test(groups = "integration")
public class PostgresSagaLogFaultTest {

    private static final String SCHEMA = "sagalog_faults";

    /** Runs each task on a thread of its own, so a test never borrows the common pool it may be measuring. */
    private static final java.util.concurrent.Executor NEW_THREAD = task -> new Thread(task).start();

    @BeforeMethod
    public void cleanSchema() throws SQLException {
        TwoProcesses.createSchema(SCHEMA);
    }

    private static String table(String instanceId, String logName) {
        return "\"" + SCHEMA + "\".\"SAGALOG_ns_" + instanceId + "_" + logName + "\"";
    }

    /**
     * sp-1: P1's session ends, P2 opens the log, reads it, truncates it and closes it. P1's next write must fail with
     * SagaLogBusyException and must not write on top of what P2 did.
     */
    @Test
    public void thatALogTakenByAnotherProcessAfterALostSessionIsNotWrittenOn() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 4);
        PostgresSagaLogPool p2 = process(SCHEMA, "02", 4);
        try {
            SagaLogId id = p1.idFor("01", "L");
            SagaLog log1 = p1.connect(id);
            log1.write(log1.builder().startSaga("exec-1", "saga", "{}")).join();
            log1.write(log1.builder().startAction("exec-1", "a")).join();
            long key = lockKey(SCHEMA, "01", "L");

            loseSessionOf(key);

            SagaLogId sameId = p2.idFor("01", "L");
            SagaLog log2 = p2.connect(sameId);
            assertEquals(log2.readIncompleteSagas().count(), 2L);
            log2.truncate().join();
            p2.remove(sameId);

            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    log1.write(log1.builder().endAction("exec-1", "a", "{}")).join();
                    fail("write " + attempt + " after another process took the log succeeded");
                } catch (RuntimeException e) {
                    assertTrue(unwrap(e) instanceof SagaLogBusyException, "write " + attempt + ": " + unwrap(e));
                }
            }
            assertEquals(query("SELECT count(*) FROM " + table("01", "L")), List.of("0"), "P2's truncate was written on");
            assertEquals(holdersOf(key), List.of(), "the log that lost its ownership still holds the lock");

            p1.remove(id);
            SagaLog again = p2.connect(sameId);
            assertEquals(again.readIncompleteSagas().count(), 0L);
        } finally {
            p2.shutdown();
            p1.shutdown();
        }
    }

    /**
     * sp-1, sp-3: while another process holds the log, a write after a lost session fails with SagaLogBusyException
     * and leaves no lock behind.
     */
    @Test
    public void thatALogHeldByAnotherProcessAfterALostSessionFailsBusy() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 4);
        PostgresSagaLogPool p2 = process(SCHEMA, "02", 4);
        try {
            SagaLogId id = p1.idFor("01", "H");
            SagaLog log1 = p1.connect(id);
            log1.write(log1.builder().startSaga("exec-1", "saga", "{}")).join();
            long key = lockKey(SCHEMA, "01", "H");
            loseSessionOf(key);
            p2.connect(p2.idFor("01", "H"));

            try {
                log1.write(log1.builder().startAction("exec-1", "a")).join();
                fail("wrote to a log held by another process");
            } catch (RuntimeException e) {
                assertTrue(unwrap(e) instanceof SagaLogBusyException, String.valueOf(unwrap(e)));
            }
            assertEquals(holdersOf(key).size(), 1, "only P2 holds the lock");
            p1.remove(id);
            assertEquals(holdersOf(key).size(), 1, "only P2 holds the lock");
        } finally {
            p2.shutdown();
            p1.shutdown();
        }
    }

    /**
     * sp-2: the statement that meets a dead connection is retried on a new session.
     */
    @Test
    public void thatAWriteThatMeetsALostSessionIsRetried() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 4);
        try {
            SagaLog log = p1.connect(p1.idFor("01", "R"));
            log.write(log.builder().startSaga("exec-1", "saga", "{}")).join();
            loseSessionOf(lockKey(SCHEMA, "01", "R"));

            log.write(log.builder().startAction("exec-1", "a")).join();
            assertEquals(log.readEntries("exec-1").map(SagaLogEntry::getEntryType).collect(Collectors.toList()),
                    List.of(SagaLogEntryType.Start, SagaLogEntryType.Start));

            loseSessionOf(lockKey(SCHEMA, "01", "R"));
            assertEquals(log.readIncompleteSagas().count(), 2L, "a read is retried too");
            loseSessionOf(lockKey(SCHEMA, "01", "R"));
            log.truncate().join();
            assertEquals(log.readIncompleteSagas().count(), 0L);
        } finally {
            p1.shutdown();
        }
    }

    /**
     * sp-3: the reconnect does not let a second operation in while it waits, and close() leaves no advisory lock on a
     * pooled connection, so another process can open the log.
     */
    @Test
    public void thatCloseAfterAReconnectLeavesNoLockOnAPooledConnection() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 6);
        PostgresSagaLogPool p2 = process(SCHEMA, "02", 4);
        try {
            SagaLogId id = p1.idFor("01", "D");
            SagaLog log1 = p1.connect(id);
            log1.write(log1.builder().startSaga("e", "s", "{}")).join();
            long key = lockKey(SCHEMA, "01", "D");
            loseSessionOf(key);
            try {
                log1.write(log1.builder().startAction("e", "sacrificial")).join();
            } catch (RuntimeException expectedBefore) {
                // before sp-2 the first write after a lost session failed without a retry
            }
            SagaLogId p2Id = p2.idFor("01", "D");
            boolean p2Holds;
            try {
                p2.connect(p2Id); // another process holds it for a moment
                p2Holds = true;
            } catch (SagaLogBusyException e) {
                p2Holds = false; // the write above took the lock back
            }
            CompletableFuture<Void> a = CompletableFuture.runAsync(() -> log1.write(log1.builder().startAction("e", "a")).join(), NEW_THREAD);
            Thread.sleep(500);
            if (p2Holds) {
                p2.remove(p2Id);
            }
            Thread.sleep(100);
            CompletableFuture<Void> c = CompletableFuture.runAsync(() -> log1.write(log1.builder().startAction("e", "c")).join(), NEW_THREAD);
            for (CompletableFuture<Void> f : List.of(c, a)) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // either may fail; what matters is the lock afterwards
                }
            }

            p1.remove(id); // close()
            awaitNoHolderOf(key);
            SagaLog reopened = p2.connect(p2Id);
            reopened.readIncompleteSagas().count();
        } finally {
            p2.shutdown();
            p1.shutdown();
        }
    }

    /**
     * sp-3: close() on a log whose session is gone leaves no lock and does not hand the dead connection back.
     */
    @Test
    public void thatCloseAfterALostSessionReleasesEverything() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 2);
        PostgresSagaLogPool p2 = process(SCHEMA, "02", 2);
        try {
            SagaLogId id = p1.idFor("01", "C");
            SagaLog log1 = p1.connect(id);
            log1.write(log1.builder().startSaga("e", "s", "{}")).join();
            loseSessionOf(lockKey(SCHEMA, "01", "C"));
            p1.remove(id);
            p2.connect(p2.idFor("01", "C")).readIncompleteSagas().count();
            // the pool still has both connections to give: the dead one was not kept
            SagaLog x = p1.connect(p1.idFor("01", "X"));
            SagaLog y = p1.connect(p1.idFor("01", "Y"));
            x.write(x.builder().startSaga("e", "s", "{}")).join();
            y.write(y.builder().startSaga("e", "s", "{}")).join();
        } finally {
            p2.shutdown();
            p1.shutdown();
        }
    }

    /**
     * sp-4: writes that wait on the database do not hold threads of the common ForkJoinPool, so other users of that
     * pool in the JVM still run.
     */
    @Test
    public void thatWritesWaitingOnTheDatabaseDoNotStarveTheCommonPool() throws Exception {
        int parallelism = ForkJoinPool.getCommonPoolParallelism();
        PostgresSagaLogPool pool = process(SCHEMA, "01", 8);
        List<Thread> writers = new ArrayList<>();
        Connection blocker = admin();
        try {
            List<SagaLog> logs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                SagaLog log = pool.connect(pool.idFor("01", "cp" + i));
                log.write(log.builder().startSaga("warm", "s", "{}")).join();
                logs.add(log);
            }
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                for (int i = 0; i < 4; i++) {
                    st.execute("LOCK TABLE " + table("01", "cp" + i) + " IN ACCESS EXCLUSIVE MODE");
                }
            }
            List<CompletableFuture<SagaLogEntry>> writes = new ArrayList<>();
            for (int i = 0; i < parallelism + 1; i++) {
                SagaLog log = logs.get(i % 4);
                CompletableFuture<SagaLogEntry> done = new CompletableFuture<>();
                writes.add(done);
                int n = i;
                Thread writer = new Thread(() -> {
                    try {
                        done.complete(log.write(log.builder().startAction("x" + n, "n" + n)).join());
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                }, "writer-" + i);
                writer.start();
                writers.add(writer);
            }
            Thread.sleep(500);
            long start = System.nanoTime();
            CompletableFuture<Long> unrelated = CompletableFuture.supplyAsync(() -> (System.nanoTime() - start) / 1_000_000);
            try {
                long waited = unrelated.get(2, TimeUnit.SECONDS);
                assertTrue(waited < 2000, "waited " + waited + " ms");
            } catch (java.util.concurrent.TimeoutException e) {
                fail("an unrelated common-pool task did not run within 2 s while " + (parallelism + 1) + " writes waited on the database");
            }
            blocker.rollback();
            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            blocker.close();
            for (Thread writer : writers) {
                writer.join(30_000);
            }
            pool.shutdown();
        }
    }

    /**
     * sp-8: a write whose commit succeeded but whose acknowledgement was lost is retried, and succeeds with the same
     * entry, stored once.
     */
    @Test
    public void thatAWriteWhoseCommitAcknowledgementWasLostSucceeds() throws Exception {
        AtomicBoolean loseNextAck = new AtomicBoolean(false);
        EmbeddedPostgresDataSource embedded = new EmbeddedPostgresDataSource("postgres", "postgres", "postgres");
        PostgresDataSource dataSource = new PostgresDataSource() {
            final Map<Connection, Connection> physical = new ConcurrentHashMap<>();

            @Override
            public Connection getConnection() throws SQLException {
                Connection real = embedded.getConnection();
                InvocationHandler handler = (proxy, method, args) -> {
                    if (method.getName().equals("commit") && loseNextAck.compareAndSet(true, false)) {
                        real.commit();
                        real.close(); // the connection is gone with the acknowledgement
                        throw new SQLException("An I/O error occurred while sending to the backend.", "08006");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                };
                Connection proxy = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, handler);
                physical.put(proxy, real);
                return proxy;
            }

            @Override
            public void evictConnection(Connection connection) {
                Connection real = physical.remove(connection);
                try {
                    if (real != null) {
                        real.close();
                    }
                } catch (SQLException ignored) {
                }
            }

            @Override
            public void close() {
            }
        };
        PostgresSagaLogPool pool = new PostgresSagaLogPool(dataSource, SCHEMA, "ns", "01");
        try {
            SagaLog log = pool.connect(pool.idFor("01", "ack"));
            log.write(log.builder().startSaga("e1", "saga", "{}")).join();
            loseNextAck.set(true);
            SagaLogEntry written = log.write(log.builder().startAction("e1", "a")).join();
            assertEquals(loseNextAck.get(), false, "the acknowledgement was lost");
            List<SagaLogEntry> entries = log.readEntries("e1").collect(Collectors.toList());
            assertEquals(entries.size(), 2, entries.toString());
            assertEquals(entries.get(1), written);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * sp-14: two processes that open the same new log at once: one opens it, the other gets SagaLogBusyException.
     */
    @Test
    public void thatTwoProcessesOpeningANewLogAtOnceGetTheLogOrBusy() throws Exception {
        PostgresSagaLogPool p1 = process(SCHEMA, "01", 4);
        PostgresSagaLogPool p2 = process(SCHEMA, "02", 4);
        try {
            List<String> unexpected = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String logName = "race" + i;
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<CompletableFuture<SagaLogId>> opens = new ArrayList<>();
                for (PostgresSagaLogPool p : List.of(p1, p2)) {
                    opens.add(CompletableFuture.supplyAsync(() -> {
                        SagaLogId id = p.idFor("01", logName);
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        p.connect(id);
                        p.remove(id);
                        return id;
                    }, NEW_THREAD));
                }
                for (CompletableFuture<SagaLogId> open : opens) {
                    try {
                        open.get(30, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (!(unwrap(e) instanceof SagaLogBusyException)) {
                            unexpected.add(logName + ": " + unwrap(e));
                        }
                    }
                }
            }
            assertTrue(unexpected.isEmpty(), unexpected.size() + " unexpected: " + unexpected);
        } finally {
            p2.shutdown();
            p1.shutdown();
        }
    }
}
