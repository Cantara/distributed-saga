package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Compensation is run and logged only for adapters that have a compensating action; CompStart is written before it
 * runs; the last entry of a node decides its state, also over several rounds of rollback and recovery.
 */
public class SagaExecutionCompensationTest {

    SelectableThreadPoolExectutor pool;
    final List<String> effects = new CopyOnWriteArrayList<>();
    final Map<String, String> abortOnce = new ConcurrentHashMap<>();
    final Map<String, Long> delay = new ConcurrentHashMap<>();
    final Map<String, Object> compensatedWith = new ConcurrentHashMap<>();

    @BeforeMethod
    public void setUp() {
        pool = new SelectableThreadPoolExectutor(16, 16, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        effects.clear();
        abortOnce.clear();
        delay.clear();
        compensatedWith.clear();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        pool.shutdownNow();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    Adapter<String> compensating(String name) {
        return new Adapter<>(String.class, name,
                (input, dependees) -> action(name),
                (input, output) -> {
                    compensatedWith.put(name, output == null ? "null" : output);
                    effects.add("undo:" + name);
                });
    }

    Adapter<String> notCompensating(String name) {
        return new Adapter<>(String.class, name, (input, dependees) -> action(name));
    }

    String action(String name) {
        Long d = delay.get(name);
        if (d != null) sleep(d);
        if (abortOnce.remove(name) != null) {
            throw new AbortSagaException("abort at " + name);
        }
        effects.add("do:" + name);
        return name + "-out";
    }

    long net(String name) {
        return effects.stream().filter(("do:" + name)::equals).count() - effects.stream().filter(("undo:" + name)::equals).count();
    }

    SagaLog newLog() {
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> "no.cantara.sagalog.memory.MemorySagaLogInitializer".equals(c.type().getName()))
                .findFirst().orElseThrow().get();
        SagaLogPool logPool = initializer.initialize(initializer.configurationOptionsAndDefaults());
        return logPool.connect(logPool.idFor(logPool.getLocalClusterInstanceId(), "c-" + UUID.randomUUID()));
    }

    static List<String> entries(SagaLog log, String executionId) {
        return log.readEntries(executionId).map(e -> e.getEntryType() + ":" + e.getNodeId()).collect(Collectors.toList());
    }

    Throwable run(SagaLog log, Saga saga, AdapterLoader adapters, String executionId, boolean recovery) {
        SagaHandoffControl control = new SagaExecution(log, pool, saga, adapters).executeSaga(executionId, "{}", recovery, r -> {
        });
        try {
            control.getCompletionFuture().get(5, TimeUnit.SECONDS);
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("saga did not complete", e);
        }
    }

    // ---- declaring a compensating action (C3) -------------------------------------------------------------------

    static class OverridingAdapter extends Adapter<String> {
        OverridingAdapter() {
            super(String.class, "overriding");
        }

        @Override
        public void executeCompensatingAction(SagaNode sagaNode, Object sagaInput, String actionOutput) {
        }
    }

    @Test
    public void thatHasCompensatingActionFollowsHowTheAdapterWasMade() {
        assertFalse(new Adapter<>(String.class, "plain").hasCompensatingAction());
        assertFalse(new Adapter<>(String.class, "action-only", (i, d) -> "x").hasCompensatingAction());
        assertFalse(new Adapter<>(String.class, "null-compensation", (i, d) -> "x", null).hasCompensatingAction());
        assertTrue(new Adapter<String>(String.class, "with-compensation", (i, d) -> "x", (i, o) -> {
        }).hasCompensatingAction());
        assertTrue(new OverridingAdapter().hasCompensatingAction(), "an override must count as a compensating action");
    }

    // ---- no compensation: nothing run, nothing logged ------------------------------------------------------------

    @Test
    public void thatStepsWithoutACompensatingActionAreNeitherCompensatedNorLogged() {
        Saga saga = Saga.start("no-comp").linkTo("txlog")
                .id("txlog").adapter("txlog").linkTo("persistence", "search")
                .id("persistence").adapter("persistence").linkToEnd()
                .id("search").adapter("search").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(notCompensating("txlog")).register(notCompensating("persistence")).register(notCompensating("search"));
        delay.put("persistence", 100L);
        abortOnce.put("persistence", "x");
        SagaLog log = newLog();
        String id = UUID.randomUUID().toString();
        assertTrue(run(log, saga, adapters, id, false) instanceof AbortSagaException);
        List<String> entries = entries(log, id);
        assertTrue(entries.stream().noneMatch(e -> e.startsWith("Comp")), entries.toString());
        assertEquals(entries.get(entries.size() - 1), "End:E");
        assertEquals(net("txlog"), 1);
        assertEquals(net("search"), 1);

        // forward recovery: steps that ended (and were not undone) are skipped, the aborted one runs
        assertNull(run(log, saga, adapters, id, true));
        assertEquals(net("txlog"), 1);
        assertEquals(net("search"), 1);
        assertEquals(net("persistence"), 1);
    }

    // ---- an unfinished step is compensated without running its action again -------------------------------------

    @Test
    public void thatAStepThatStartedButDidNotEndIsCompensatedWithNullOutputAndNotRunAgain() {
        // q waits for p and slow; r aborts at once, so q is not visited in this round and keeps its old Start
        Saga saga = Saga.start("unfinished").linkTo("r", "p", "slow")
                .id("r").adapter("r").linkToEnd()
                .id("p").adapter("p").linkTo("q")
                .id("slow").adapter("slow").linkTo("q")
                .id("q").adapter("q").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader()
                .register(compensating("r")).register(compensating("p")).register(compensating("slow")).register(compensating("q"));
        SagaLog log = newLog();
        String id = UUID.randomUUID().toString();
        // the log of an earlier run that crashed while q was running
        log.write(new SagaLogEntryBuilder().startSaga(id, "unfinished", "{}")).join();
        log.write(new SagaLogEntryBuilder().startAction(id, "p")).join();
        log.write(new SagaLogEntryBuilder().endAction(id, "p", "p-out")).join();
        log.write(new SagaLogEntryBuilder().startAction(id, "slow")).join();
        log.write(new SagaLogEntryBuilder().endAction(id, "slow", "slow-out")).join();
        log.write(new SagaLogEntryBuilder().startAction(id, "q")).join();
        abortOnce.put("r", "x");

        assertTrue(run(log, saga, adapters, id, true) instanceof AbortSagaException);
        assertFalse(effects.contains("do:q"), "q's action must not run during rollback: " + effects);
        assertEquals(compensatedWith.get("q"), "null");
        assertEquals(compensatedWith.get("p"), "p-out");
        List<String> entries = entries(log, id);
        assertTrue(entries.indexOf("CompStart:q") < entries.indexOf("Comp:q"), entries.toString());
        assertTrue(entries.indexOf("Comp:q") < entries.indexOf("Comp:p"), "dependents before dependencies: " + entries);
    }

    // ---- several rounds: the last entry decides (C2) --------------------------------------------------------------

    @Test
    public void thatTheLastRoundDecidesOverRepeatedRollbackAndRecovery() {
        Saga saga = Saga.start("rounds").linkTo("a", "b", "c")
                .id("a").adapter("a").linkToEnd()
                .id("b").adapter("b").linkToEnd()
                .id("c").adapter("c").linkToEnd()
                .end();
        AdapterLoader adapters = new AdapterLoader().register(compensating("a")).register(compensating("b")).register(compensating("c"));
        SagaLog log = newLog();
        String id = UUID.randomUUID().toString();

        // round 1: c aborts after a and b ended; a and b are compensated
        delay.put("c", 100L);
        abortOnce.put("c", "x");
        assertTrue(run(log, saga, adapters, id, false) instanceof AbortSagaException);
        assertEquals(List.of(net("a"), net("b"), net("c")), List.of(0L, 0L, 0L));

        // round 2: forward recovery runs a, b and c again; a aborts this time, after b and c ended
        delay.clear();
        delay.put("a", 100L);
        abortOnce.put("a", "x");
        assertTrue(run(log, saga, adapters, id, true) instanceof AbortSagaException);
        // b and c ended in round 2 and must be compensated although they (b) or an old Abort (c) say otherwise
        assertEquals(List.of(net("a"), net("b"), net("c")), List.of(0L, 0L, 0L), "after round 2: " + effects + " " + entries(log, id));

        // round 3: forward recovery without faults: every effect in place exactly once
        delay.clear();
        assertNull(run(log, saga, adapters, id, true));
        assertEquals(List.of(net("a"), net("b"), net("c")), List.of(1L, 1L, 1L), "after round 3: " + effects + " " + entries(log, id));
        List<String> entries = entries(log, id);
        assertEquals(entries.get(entries.size() - 1), "End:E");
    }

    @Test
    public void thatForwardRecoveryPassesOnTheLatestOutput() {
        Saga saga = Saga.start("latest").linkTo("a")
                .id("a").adapter("a").linkTo("b")
                .id("b").adapter("b").linkToEnd()
                .end();
        List<Object> seenByB = Collections.synchronizedList(new ArrayList<>());
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "a", (i, d) -> "unused"))
                .register(new Adapter<>(String.class, "b", (i, d) -> {
                    seenByB.add(d.values().iterator().next());
                    return "b-out";
                }));
        SagaLog log = newLog();
        String id = UUID.randomUUID().toString();
        // two rounds of a: the second End is the one that counts
        log.write(new SagaLogEntryBuilder().startSaga(id, "latest", "{}")).join();
        log.write(new SagaLogEntryBuilder().startAction(id, "a")).join();
        log.write(new SagaLogEntryBuilder().endAction(id, "a", "first")).join();
        log.write(new SagaLogEntryBuilder().compStart(id, "a")).join();
        log.write(new SagaLogEntryBuilder().compDone(id, "a")).join();
        log.write(new SagaLogEntryBuilder().startAction(id, "a")).join();
        log.write(new SagaLogEntryBuilder().endAction(id, "a", "second")).join();
        assertNull(run(log, saga, adapters, id, true));
        assertEquals(seenByB, List.of("second"));
    }

    // ---- a compensation that fails leaves the saga for recovery (C1) --------------------------------------------

    @Test
    public void thatAFailedCompensationIsRecoveredForward() {
        Saga saga = Saga.start("comp-fails").linkTo("a", "b")
                .id("a").adapter("a").linkToEnd()
                .id("b").adapter("b").linkToEnd()
                .end();
        boolean[] failCompensation = {true};
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(String.class, "a", (i, d) -> action("a"), (i, o) -> {
                    effects.add("undo:a"); // the effect is undone, then the compensation fails before Comp is written
                    if (failCompensation[0]) {
                        failCompensation[0] = false;
                        throw new IllegalStateException("compensation failed");
                    }
                }))
                .register(compensating("b"));
        delay.put("b", 100L);
        abortOnce.put("b", "x");
        SagaLog log = newLog();
        String id = UUID.randomUUID().toString();
        Throwable failure = run(log, saga, adapters, id, false);
        assertTrue(failure instanceof IllegalStateException, String.valueOf(failure));
        List<String> entries = entries(log, id);
        assertTrue(entries.contains("CompStart:a") && !entries.contains("Comp:a") && !entries.contains("End:E"), entries.toString());

        // forward recovery runs a again (its last entry is CompStart), so its effect is in place
        assertNull(run(log, saga, adapters, id, true));
        assertEquals(net("a"), 1);
        assertEquals(net("b"), 1);
    }
}
