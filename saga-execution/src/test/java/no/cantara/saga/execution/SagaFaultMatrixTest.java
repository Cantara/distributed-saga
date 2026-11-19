package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.testng.Assert.fail;

/**
 * Fault-injection matrix: every saga shape x every step x every failure point x every failure kind, on the memory
 * saga log, each row run several times to catch races.
 * <p>
 * Acceptance, checked after every run:
 * <ol>
 * <li>the completion future completes (no hang), and onComplete is called exactly once;</li>
 * <li>no pool thread stays busy;</li>
 * <li>a failed saga fails with the injected exception itself;</li>
 * <li>the saga ends in one defined state: SUCCEEDED (End on every node and on E), ROLLED_BACK (Abort, every ended node
 * compensated once, dependents before their dependencies, End on E last), or UNFINISHED (no End on E);</li>
 * <li>an UNFINISHED saga is completed by forward recovery once the fault is gone, without running again a node that
 * had already ended;</li>
 * <li>a crash at any point of a run (the log cut after any entry) is completed by forward recovery.</li>
 * </ol>
 */
public class SagaFaultMatrixTest {

    static final int REPEAT = Integer.getInteger("matrix.repeat", 3); // -Dmatrix.repeat=200 for a stress run

    // ---- shapes -------------------------------------------------------------------------------------------------

    static final Map<String, Saga> SHAPES = new LinkedHashMap<>();

    static {
        SHAPES.put("lds-write", Saga.start("lds-write").linkTo("txlog")
                .id("txlog").adapter("txlog").linkTo("persistence", "search")
                .id("persistence").adapter("persistence").linkToEnd()
                .id("search").adapter("search").linkToEnd()
                .end());
        SHAPES.put("lds-linear", Saga.start("lds-linear").linkTo("txlog")
                .id("txlog").adapter("txlog").linkTo("persistence")
                .id("persistence").adapter("persistence").linkToEnd()
                .end());
        SHAPES.put("diamond", Saga.start("diamond").linkTo("a")
                .id("a").adapter("a").linkTo("b", "c")
                .id("b").adapter("b").linkTo("d")
                .id("c").adapter("c").linkTo("d")
                .id("d").adapter("d").linkToEnd()
                .end());
        SHAPES.put("fan-out", Saga.start("fan-out").linkTo("a", "b", "c", "d")
                .id("a").adapter("a").linkToEnd()
                .id("b").adapter("b").linkToEnd()
                .id("c").adapter("c").linkToEnd()
                .id("d").adapter("d").linkToEnd()
                .end());
    }

    static List<String> steps(Saga saga) {
        return saga.nodes().stream().map(n -> n.id)
                .filter(id -> !Saga.ID_START.equals(id) && !Saga.ID_END.equals(id))
                .collect(Collectors.toList());
    }

    // ---- faults -------------------------------------------------------------------------------------------------

    enum Kind {ABORT, RUNTIME, ERROR}

    enum Point {
        ACTION_BEFORE_EFFECT, ACTION_AFTER_EFFECT, LOG_WRITE_START, LOG_WRITE_END
    }

    static Throwable newFault(Kind kind, String where) {
        switch (kind) {
            case ABORT:
                return new AbortSagaException("injected abort at " + where);
            case RUNTIME:
                return new IllegalStateException("injected failure at " + where);
            default:
                return new InjectedError("injected error at " + where);
        }
    }

    static class InjectedError extends Error {
        InjectedError(String message) {
            super(message);
        }
    }

    static void sneakyThrow(Throwable t) {
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        throw new IllegalStateException(t);
    }

    /**
     * Records the side effects of actions and compensating actions, and throws the faults that are armed.
     */
    static class World {
        final List<String> effects = new CopyOnWriteArrayList<>();
        final Map<String, Throwable> actionFaultBefore = new ConcurrentHashMap<>();
        final Map<String, Throwable> actionFaultAfter = new ConcurrentHashMap<>();
        final Map<String, Throwable> compensationFault = new ConcurrentHashMap<>();
        final Map<String, Long> delayMs = new ConcurrentHashMap<>();

        AdapterLoader adapters(Saga saga) {
            AdapterLoader loader = new AdapterLoader();
            for (String step : steps(saga)) {
                loader.register(new Adapter<>(String.class, step,
                        (input, dependees) -> {
                            Long delay = delayMs.get(step);
                            if (delay != null) sleep(delay);
                            Throwable before = actionFaultBefore.remove(step);
                            if (before != null) sneakyThrow(before);
                            effects.add("do:" + step);
                            Throwable after = actionFaultAfter.remove(step);
                            if (after != null) sneakyThrow(after);
                            return step + "-out";
                        },
                        (input, output) -> {
                            Throwable t = compensationFault.remove(step);
                            if (t != null) sneakyThrow(t);
                            effects.add("undo:" + step);
                        }));
            }
            return loader;
        }

        long count(String effect) {
            return effects.stream().filter(effect::equals).count();
        }
    }

    /**
     * A saga log that fails one chosen write (the write never reaches the log).
     */
    static class FaultySagaLog implements SagaLog {
        final SagaLog delegate;
        volatile Predicate<SagaLogEntryBuilder> failWhen;
        volatile Throwable failure;

        FaultySagaLog(SagaLog delegate) {
            this.delegate = delegate;
        }

        void failOnce(SagaLogEntryType type, String nodeId, Throwable failure) {
            this.failure = failure;
            this.failWhen = b -> b.entryType() == type && nodeId.equals(b.nodeId());
        }

        @Override
        public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
            Predicate<SagaLogEntryBuilder> p = failWhen;
            if (p != null && p.test(builder)) {
                failWhen = null;
                return CompletableFuture.failedFuture(failure);
            }
            return delegate.write(builder);
        }

        @Override public SagaLogId id() { return delegate.id(); }
        @Override public CompletableFuture<Void> truncate(SagaLogEntryId id) { return delegate.truncate(id); }
        @Override public CompletableFuture<Void> truncate() { return delegate.truncate(); }
        @Override public Stream<SagaLogEntry> readIncompleteSagas() { return delegate.readIncompleteSagas(); }
        @Override public Stream<SagaLogEntry> readEntries(String executionId) { return delegate.readEntries(executionId); }
        @Override public String toString(SagaLogEntryId id) { return delegate.toString(id); }
        @Override public SagaLogEntryId fromString(String id) { return delegate.fromString(id); }
        @Override public byte[] toBytes(SagaLogEntryId id) { return delegate.toBytes(id); }
        @Override public SagaLogEntryId fromBytes(byte[] idBytes) { return delegate.fromBytes(idBytes); }
        @Override public void close() throws Exception { delegate.close(); }
    }

    // ---- infrastructure -----------------------------------------------------------------------------------------

    SelectableThreadPoolExectutor pool;
    SagaLogPool logPool;

    @BeforeClass
    public void setUp() {
        pool = new SelectableThreadPoolExectutor(32, 32, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> "no.cantara.sagalog.memory.MemorySagaLogInitializer".equals(c.type().getName()))
                .findFirst().orElseThrow().get();
        logPool = initializer.initialize(initializer.configurationOptionsAndDefaults());
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        pool.shutdownNow();
    }

    FaultySagaLog newLog() {
        return new FaultySagaLog(logPool.connect(logPool.idFor(logPool.getLocalClusterInstanceId(), "m-" + UUID.randomUUID())));
    }

    static List<String> entries(SagaLog log, String executionId) {
        return log.readEntries(executionId).map(e -> e.getEntryType() + ":" + e.getNodeId()).collect(Collectors.toList());
    }

    static class Run {
        String executionId;
        Throwable failure;      // cause of the completion future, null on success
        boolean hung;
        int onCompleteCalls;
        int busyThreadsAfter;
        List<String> entries;
    }

    Run execute(Saga saga, World world, FaultySagaLog log, String executionId, boolean recovery) {
        Run run = new Run();
        run.executionId = executionId;
        AtomicInteger onComplete = new AtomicInteger();
        SagaHandoffControl control;
        try {
            control = new SagaExecution(log, pool, saga, world.adapters(saga))
                    .executeSaga(executionId, "{}", recovery, r -> onComplete.incrementAndGet());
            control.getCompletionFuture().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            run.hung = true;
        } catch (ExecutionException e) {
            run.failure = e.getCause();
        } catch (Throwable t) {
            run.failure = t; // thrown by executeSaga itself
        }
        long deadline = System.currentTimeMillis() + 2_000;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            sleep(5);
        }
        sleep(10); // a late second onComplete call would show here
        run.busyThreadsAfter = pool.getActiveCount();
        run.onCompleteCalls = onComplete.get();
        run.entries = entries(log, executionId);
        return run;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- acceptance ---------------------------------------------------------------------------------------------

    enum State {SUCCEEDED, ROLLED_BACK, UNFINISHED, UNDEFINED}

    static State state(Saga saga, List<String> entries, List<String> problems) {
        boolean endOfSaga = entries.contains("End:E");
        boolean aborted = entries.stream().anyMatch(e -> e.startsWith("Abort:"));
        if (!endOfSaga) {
            return State.UNFINISHED;
        }
        if (!entries.get(entries.size() - 1).equals("End:E")) {
            problems.add("End:E is not the last entry");
        }
        boolean allEnded = steps(saga).stream().allMatch(step -> entries.contains("End:" + step));
        if (allEnded && entries.lastIndexOf("End:E") > lastIndexOfAny(entries, "Abort:", "Comp:")) {
            return State.SUCCEEDED; // also an aborted run completed by forward recovery
        }
        if (!aborted) {
            problems.add("End:E without End on every step and without Abort");
            return State.UNDEFINED;
        }
        // rolled back: every node that ended is compensated once, dependents before dependencies
        for (String step : steps(saga)) {
            long comps = entries.stream().filter(("Comp:" + step)::equals).count();
            boolean ended = entries.contains("End:" + step);
            if (ended && comps != 1) problems.add("ended node " + step + " compensated " + comps + " times");
            if (!ended && comps != 0) problems.add("node " + step + " compensated without End");
        }
        for (SagaNode node : saga.nodes()) {
            int ci = entries.indexOf("Comp:" + node.id);
            if (ci < 0) continue;
            for (SagaNode dependent : node.outgoing) {
                int di = entries.indexOf("Comp:" + dependent.id);
                if (di > ci) problems.add("Comp:" + node.id + " before its dependent Comp:" + dependent.id);
            }
        }
        return problems.isEmpty() ? State.ROLLED_BACK : State.UNDEFINED;
    }

    static int lastIndexOfAny(List<String> entries, String... prefixes) {
        int last = -1;
        for (int i = 0; i < entries.size(); i++) {
            for (String prefix : prefixes) {
                if (entries.get(i).startsWith(prefix)) last = i;
            }
        }
        return last;
    }

    /**
     * Net effect of each step: actions done minus actions undone. A succeeded saga must have every step's effect
     * at least once (actions are idempotent and may run again); a rolled-back saga none, except the aborted
     * step, whose own effect before the abort is the adapter's to clean up (the library does not compensate it).
     */
    static void checkNetEffects(Saga saga, State state, Map<String, Long> net, String abortedStep, List<String> problems) {
        for (String step : steps(saga)) {
            long n = net.getOrDefault(step, 0L);
            if (state == State.SUCCEEDED && n < 1) problems.add("succeeded but the effect of " + step + " is " + n);
            if (state == State.ROLLED_BACK && n != 0 && !step.equals(abortedStep)) {
                problems.add("rolled back but the effect of " + step + " is " + n);
            }
        }
    }

    static Map<String, Long> net(World world, Saga saga) {
        Map<String, Long> net = new LinkedHashMap<>();
        for (String step : steps(saga)) net.put(step, world.count("do:" + step) - world.count("undo:" + step));
        return net;
    }

    static boolean isOrCauses(Throwable actual, Throwable expected) {
        for (Throwable t = actual; t != null; t = t.getCause()) {
            if (t == expected) return true;
        }
        return false;
    }

    /**
     * Checks one run against the acceptance rules; returns the violations, empty when the run is accepted.
     */
    List<String> check(Saga saga, World world, FaultySagaLog log, Run run, Throwable injected, String label) {
        List<String> problems = new ArrayList<>();
        if (run.hung) problems.add("completion future did not complete within 5 s");
        if (run.onCompleteCalls != 1) problems.add("onComplete called " + run.onCompleteCalls + " times");
        if (run.busyThreadsAfter != 0) problems.add(run.busyThreadsAfter + " pool threads still busy");
        if (injected != null && !run.hung) {
            if (run.failure == null) problems.add("saga succeeded although " + injected + " was injected");
            else if (run.failure != injected) {
                problems.add("failed with " + describe(run.failure) + (isOrCauses(run.failure, injected) ? " (wraps the injected one)" : " instead of the injected " + injected));
            }
        }
        State state = state(saga, run.entries, problems);
        String abortedStep = run.entries.stream().filter(e -> e.startsWith("Abort:")).map(e -> e.substring(6)).findFirst().orElse(null);
        if (state == State.SUCCEEDED || state == State.ROLLED_BACK) {
            checkNetEffects(saga, state, net(world, saga), abortedStep, problems);
        }
        if (state == State.UNFINISHED && !run.hung) {
            // forward recovery once the fault is gone
            List<String> endedBefore = run.entries.stream().filter(e -> e.startsWith("End:")).map(e -> e.substring(4)).collect(Collectors.toList());
            Map<String, Long> doneBefore = new LinkedHashMap<>();
            for (String step : steps(saga)) doneBefore.put(step, world.count("do:" + step));
            Run recovered = execute(saga, world, log, run.executionId, true);
            if (recovered.hung) problems.add("recovery hung");
            if (recovered.failure != null) problems.add("recovery failed with " + describe(recovered.failure));
            List<String> rp = new ArrayList<>();
            State after = state(saga, recovered.entries, rp);
            if (after != State.SUCCEEDED) problems.add("after recovery " + after + " " + rp + " " + recovered.entries);
            else checkNetEffects(saga, after, net(world, saga), null, problems);
            for (String step : endedBefore) {
                if (doneBefore.containsKey(step) && world.count("do:" + step) != doneBefore.get(step)) {
                    problems.add("recovery ran " + step + " again although it had ended");
                }
            }
        }
        if (!problems.isEmpty()) {
            problems.add(0, label + " -> " + state + " " + run.entries + " effects " + world.effects);
        }
        return problems;
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName()).append('(').append(c.getMessage()).append(')');
        }
        return sb.toString();
    }

    // ---- rows ---------------------------------------------------------------------------------------------------

    @DataProvider(name = "faults")
    public Object[][] faults() {
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            for (String step : steps(SHAPES.get(shape))) {
                for (Point point : Point.values()) {
                    for (Kind kind : Kind.values()) {
                        rows.add(new Object[]{shape, step, point, kind});
                    }
                }
            }
        }
        return rows.toArray(new Object[0][]);
    }

    @Test(dataProvider = "faults")
    public void fault(String shape, String step, Point point, Kind kind) {
        Saga saga = SHAPES.get(shape);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            World world = new World();
            FaultySagaLog log = newLog();
            String where = shape + "/" + step + "/" + point + "/" + kind;
            Throwable injected = newFault(kind, where);
            switch (point) {
                case ACTION_BEFORE_EFFECT:
                    world.actionFaultBefore.put(step, injected);
                    break;
                case ACTION_AFTER_EFFECT:
                    world.actionFaultAfter.put(step, injected);
                    break;
                case LOG_WRITE_START:
                    log.failOnce(SagaLogEntryType.Start, step, injected);
                    break;
                case LOG_WRITE_END:
                    log.failOnce(SagaLogEntryType.End, step, injected);
                    break;
            }
            Run run = execute(saga, world, log, UUID.randomUUID().toString(), false);
            violations.addAll(check(saga, world, log, run, injected, where + " run " + i));
        }
        report(violations);
    }

    @DataProvider(name = "compensationFaults")
    public Object[][] compensationFaults() {
        // the last step aborts; a step that has ended fails in its compensating action
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            List<String> steps = steps(SHAPES.get(shape));
            String aborting = steps.get(steps.size() - 1);
            for (String step : steps) {
                if (step.equals(aborting)) continue;
                for (Kind kind : new Kind[]{Kind.RUNTIME, Kind.ERROR}) {
                    rows.add(new Object[]{shape, aborting, step, kind});
                }
            }
        }
        return rows.toArray(new Object[0][]);
    }

    @Test(dataProvider = "compensationFaults")
    public void compensationFault(String shape, String aborting, String step, Kind kind) {
        Saga saga = SHAPES.get(shape);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            World world = new World();
            FaultySagaLog log = newLog();
            String where = shape + "/abort at " + aborting + "/compensation of " + step + "/" + kind;
            Throwable abort = newFault(Kind.ABORT, where);
            Throwable injected = newFault(kind, where);
            world.delayMs.put(aborting, 100L); // let the other branches end first, so they are compensated
            world.actionFaultBefore.put(aborting, abort);
            world.compensationFault.put(step, injected);
            Run run = execute(saga, world, log, UUID.randomUUID().toString(), false);
            List<String> problems = new ArrayList<>();
            if (run.hung) problems.add("completion future did not complete within 5 s");
            if (run.onCompleteCalls != 1) problems.add("onComplete called " + run.onCompleteCalls + " times");
            if (run.busyThreadsAfter != 0) problems.add(run.busyThreadsAfter + " pool threads still busy");
            if (!run.hung && run.failure == null) problems.add("saga succeeded");
            State state = state(saga, run.entries, problems);
            if (state == State.ROLLED_BACK) problems.add("reported ROLLED_BACK although compensation of " + step + " failed");
            if (!problems.isEmpty()) {
                problems.add(0, where + " run " + i + " -> " + state + " failed with " + (run.failure == null ? "-" : describe(run.failure)) + " " + run.entries);
            }
            violations.addAll(problems);
        }
        report(violations);
    }

    @DataProvider(name = "crashes")
    public Object[][] crashes() {
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            rows.add(new Object[]{shape, "success"});
            rows.add(new Object[]{shape, "abort"});
        }
        return rows.toArray(new Object[0][]);
    }

    /**
     * A crash after any entry of a run: the log holds a prefix of that run's entries. Forward recovery must complete it.
     */
    @Test(dataProvider = "crashes")
    public void crash(String shape, String runKind) {
        crash(shape, runKind, false);
    }

    /**
     * Finding M1, open: a crash after some steps were compensated, then forward recovery. Recovery skips the
     * compensated steps (they have End), writes End on E and reports success, while their effects stay undone.
     * Enable once the behaviour is decided.
     */
    @Test(dataProvider = "crashes", enabled = false)
    public void crashDuringRollback(String shape, String runKind) {
        if (runKind.equals("abort")) {
            crash(shape, runKind, true);
        }
    }

    void crash(String shape, String runKind, boolean duringRollback) {
        Saga saga = SHAPES.get(shape);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            World world = new World();
            FaultySagaLog full = newLog();
            List<String> steps = steps(saga);
            if (runKind.equals("abort")) {
                world.actionFaultBefore.put(steps.get(steps.size() - 1), newFault(Kind.ABORT, shape));
            }
            Run run = execute(saga, world, full, UUID.randomUUID().toString(), false);
            List<SagaLogEntry> written = full.readEntries(run.executionId).collect(Collectors.toList());
            for (int cut = 1; cut < written.size(); cut++) {
                List<SagaLogEntry> prefix = written.subList(0, cut);
                if (!duringRollback && prefix.stream().anyMatch(e -> e.getEntryType() == SagaLogEntryType.Comp)) {
                    continue; // a crash during the rollback itself: see crashDuringRollback
                }
                if (duringRollback && prefix.stream().noneMatch(e -> e.getEntryType() == SagaLogEntryType.Comp)) {
                    continue;
                }
                FaultySagaLog log = newLog();
                for (SagaLogEntry e : prefix) {
                    log.write(new SagaLogEntryBuilder().executionId(e.getExecutionId()).entryType(e.getEntryType())
                            .nodeId(e.getNodeId()).sagaName(e.getSagaName()).jsonData(e.getJsonData())).join();
                }
                World after = new World();
                Run recovered = execute(saga, after, log, run.executionId, true);
                List<String> problems = new ArrayList<>();
                if (recovered.hung) problems.add("recovery hung");
                if (recovered.failure != null) problems.add("recovery failed with " + describe(recovered.failure));
                if (recovered.onCompleteCalls != 1) problems.add("onComplete called " + recovered.onCompleteCalls + " times");
                if (recovered.busyThreadsAfter != 0) problems.add(recovered.busyThreadsAfter + " pool threads still busy");
                List<String> rp = new ArrayList<>();
                State state = state(saga, recovered.entries, rp);
                if (state != State.SUCCEEDED) problems.add("after recovery " + state + " " + rp);
                List<String> cutEntries = prefix.stream().map(e -> e.getEntryType() + ":" + e.getNodeId()).collect(Collectors.toList());
                if (state == State.SUCCEEDED) {
                    Map<String, Long> net = net(after, saga);
                    for (String step : steps) {
                        long before = (cutEntries.contains("End:" + step) ? 1 : 0) - cutEntries.stream().filter(("Comp:" + step)::equals).count();
                        net.merge(step, before, Long::sum);
                    }
                    checkNetEffects(saga, state, net, null, problems);
                }
                for (String step : steps) {
                    if (cutEntries.contains("End:" + step) && after.count("do:" + step) > 0) {
                        problems.add("recovery ran " + step + " again although it had ended");
                    }
                }
                if (!problems.isEmpty()) {
                    problems.add(0, shape + "/" + runKind + " crash after " + cutEntries + " run " + i + " -> " + recovered.entries);
                    violations.addAll(problems);
                    break;
                }
            }
        }
        report(violations);
    }

    static void report(List<String> violations) {
        if (!violations.isEmpty()) {
            System.out.println("MATRIX-FINDING " + String.join(" | ", violations));
            fail(String.join("\n", violations));
        }
    }
}
