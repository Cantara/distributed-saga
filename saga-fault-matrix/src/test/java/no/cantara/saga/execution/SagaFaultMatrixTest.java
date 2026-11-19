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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
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
 * Runs once per saga-log provider (the concrete subclasses). Every log a row opens is removed and deleted after it.
 */
public abstract class SagaFaultMatrixTest {

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
        // fm-1: a step between a failing step and the join it reaches, so the join waits for a step that is never run
        SHAPES.put("gap-1", Saga.start("gap-1").linkTo("f", "a")
                .id("f").adapter("f").linkTo("b")
                .id("b").adapter("b").linkTo("j")
                .id("a").adapter("a").linkTo("j")
                .id("j").adapter("j").linkToEnd()
                .end());
        // fm-1: the failing branch two steps from the join
        SHAPES.put("gap-2", Saga.start("gap-2").linkTo("f", "a")
                .id("f").adapter("f").linkTo("b")
                .id("b").adapter("b").linkTo("c")
                .id("c").adapter("c").linkTo("j")
                .id("a").adapter("a").linkTo("j")
                .id("j").adapter("j").linkToEnd()
                .end());
        // fm-1, the backward twin: two branches of two steps each into E; one aborts, a compensation on the other
        // fails, and the backward join at x waits for a step that the failed backward walk never reaches
        SHAPES.put("gap-backward", Saga.start("gap-backward").linkTo("x")
                .id("x").adapter("x").linkTo("p", "q")
                .id("p").adapter("p").linkTo("z")
                .id("q").adapter("q").linkTo("r")
                .id("r").adapter("r").linkToEnd()
                .id("z").adapter("z").linkToEnd()
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
     * A saga log that fails one chosen write (the write never reaches the log), or the first read of the entries
     * after an entry of a chosen type was written (fm-2).
     */
    static class FaultySagaLog implements SagaLog {
        final SagaLog delegate;
        volatile Predicate<SagaLogEntryBuilder> failWhen;
        volatile Throwable failure;
        volatile SagaLogEntryType failReadAfter;
        volatile Throwable readFailure;
        volatile boolean readArmed;

        FaultySagaLog(SagaLog delegate) {
            this.delegate = delegate;
        }

        void failOnce(SagaLogEntryType type, String nodeId, Throwable failure) {
            this.failure = failure;
            this.failWhen = b -> b.entryType() == type && nodeId.equals(b.nodeId());
        }

        /**
         * Fails the first {@code readEntries} after an entry of the given type has been written.
         */
        void failReadOnceAfter(SagaLogEntryType type, Throwable failure) {
            this.readFailure = failure;
            this.failReadAfter = type;
        }

        @Override
        public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
            Predicate<SagaLogEntryBuilder> p = failWhen;
            if (p != null && p.test(builder)) {
                failWhen = null;
                return CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<SagaLogEntry> written = delegate.write(builder);
            if (failReadAfter != null && builder.entryType() == failReadAfter) {
                failReadAfter = null;
                readArmed = true;
            }
            return written;
        }

        @Override
        public Stream<SagaLogEntry> readEntries(String executionId) {
            if (readArmed) {
                readArmed = false;
                sneakyThrow(readFailure);
            }
            return delegate.readEntries(executionId);
        }

        @Override public SagaLogId id() { return delegate.id(); }
        @Override public CompletableFuture<Void> truncate(SagaLogEntryId id) { return delegate.truncate(id); }
        @Override public CompletableFuture<Void> truncate() { return delegate.truncate(); }
        @Override public Stream<SagaLogEntry> readIncompleteSagas() { return delegate.readIncompleteSagas(); }
        @Override public String toString(SagaLogEntryId id) { return delegate.toString(id); }
        @Override public SagaLogEntryId fromString(String id) { return delegate.fromString(id); }
        @Override public byte[] toBytes(SagaLogEntryId id) { return delegate.toBytes(id); }
        @Override public SagaLogEntryId fromBytes(byte[] idBytes) { return delegate.fromBytes(idBytes); }
        @Override public void close() throws Exception { delegate.close(); }
    }

    // ---- infrastructure -----------------------------------------------------------------------------------------

    SelectableThreadPoolExectutor pool;
    SagaLogPool logPool;

    final List<SagaLogId> openedLogs = new CopyOnWriteArrayList<>();

    /**
     * The saga-log pool of the provider under test.
     */
    protected abstract SagaLogPool createLogPool() throws Exception;

    /**
     * Called after the pool is shut down.
     */
    protected void closeProvider() throws Exception {
    }

    static SagaLogPool initialize(String initializerClass, Map<String, String> overrides) {
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> initializerClass.equals(c.type().getName()))
                .findFirst().orElseThrow(() -> new IllegalStateException("no provider " + initializerClass)).get();
        Map<String, String> configuration = new LinkedHashMap<>(initializer.configurationOptionsAndDefaults());
        configuration.putAll(overrides);
        return initializer.initialize(configuration);
    }

    @BeforeClass
    public void setUp() throws Exception {
        pool = new SelectableThreadPoolExectutor(32, 32, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        logPool = createLogPool();
    }

    @AfterMethod(alwaysRun = true)
    public void removeLogs() {
        for (SagaLogId id : openedLogs) {
            logPool.remove(id);
            logPool.delete(id);
        }
        openedLogs.clear();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        pool.shutdownNow();
        if (logPool != null) {
            logPool.shutdown();
        }
        closeProvider();
    }

    /**
     * Removes and deletes a log before the row ends; a provider may hold a resource per open log (PostgreSQL: one
     * connection), so a row that opens many logs gives each back when done with it.
     */
    void discard(FaultySagaLog log) {
        SagaLogId id = log.id();
        if (openedLogs.remove(id)) {
            logPool.remove(id);
            logPool.delete(id);
        }
    }

    FaultySagaLog newLog() {
        SagaLogId id = logPool.idFor(logPool.getLocalClusterInstanceId(), "m" + UUID.randomUUID().toString().replace("-", ""));
        openedLogs.add(id);
        return new FaultySagaLog(logPool.connect(id));
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
        volatile List<String> entriesAtOnComplete; // what onComplete saw; null if it was not called
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        Future<SagaHandoffResult> completion;
    }

    Run execute(Saga saga, World world, FaultySagaLog log, String executionId, boolean recovery) {
        Run run = start(pool, saga, world, log, executionId, recovery);
        awaitCompletion(run, 5_000);
        return finish(pool, log, run);
    }

    /**
     * Starts a saga on the given pool. A failure thrown by executeSaga itself is the run's failure.
     */
    static Run start(SelectableThreadPoolExectutor executor, Saga saga, World world, FaultySagaLog log, String executionId, boolean recovery) {
        Run run = new Run();
        run.executionId = executionId;
        try {
            SagaHandoffControl control = new SagaExecution(log, executor, saga, world.adapters(saga))
                    .executeSaga(executionId, "{}", recovery, r -> {
                        // se-2: the entries when the saga reports completion are all the entries it ever writes
                        run.entriesAtOnComplete = entries(log, executionId);
                        run.onCompleteCounter.incrementAndGet();
                    });
            run.completion = control.getCompletionFuture();
        } catch (Throwable t) {
            run.failure = t; // thrown by executeSaga itself
        }
        return run;
    }

    static void awaitCompletion(Run run, long ms) {
        if (run.completion == null) {
            return;
        }
        try {
            run.completion.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            run.hung = true;
        } catch (ExecutionException e) {
            run.failure = e.getCause();
        } catch (Throwable t) {
            run.failure = t;
        }
    }

    static Run finish(SelectableThreadPoolExectutor executor, FaultySagaLog log, Run run) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (executor.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            sleep(5);
        }
        sleep(10); // a late second onComplete call would show here
        run.busyThreadsAfter = executor.getActiveCount();
        run.onCompleteCalls = run.onCompleteCounter.get();
        run.entries = entries(log, run.executionId);
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
        if (allEnded && entries.lastIndexOf("End:E") > lastIndexOfAny(entries, "Abort:", "Comp:", "CompStart:")
                && steps(saga).stream().allMatch(step -> lastOf(entries, step).equals("End"))) {
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

    static String lastOf(List<String> entries, String step) {
        String last = "";
        for (String e : entries) {
            if (e.endsWith(":" + step)) last = e.substring(0, e.indexOf(':'));
        }
        return last;
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
        if (injected != null && !run.hung) {
            if (run.failure == null) problems.add("saga succeeded although " + injected + " was injected");
            else if (run.failure != injected) {
                problems.add("failed with " + describe(run.failure) + (isOrCauses(run.failure, injected) ? " (wraps the injected one)" : " instead of the injected " + injected));
            }
        }
        return check(saga, world, log, run, problems, label);
    }

    /**
     * Checks one run whose outcome (null for success, else the failure) must satisfy {@code accepted}.
     */
    List<String> check(Saga saga, World world, FaultySagaLog log, Run run, Predicate<Throwable> accepted, String expected, String label) {
        List<String> problems = new ArrayList<>();
        if (!run.hung && !accepted.test(run.failure)) {
            problems.add((run.failure == null ? "succeeded" : "failed with " + describe(run.failure)) + ", expected " + expected);
        }
        return check(saga, world, log, run, problems, label);
    }

    private List<String> check(Saga saga, World world, FaultySagaLog log, Run run, List<String> problems, String label) {
        if (run.hung) problems.add("completion future did not complete within 5 s");
        if (run.onCompleteCalls != 1) problems.add("onComplete called " + run.onCompleteCalls + " times");
        if (run.busyThreadsAfter != 0) problems.add(run.busyThreadsAfter + " pool threads still busy");
        checkNothingWrittenAfterCompletion(run, problems);
        State state = state(saga, run.entries, problems);
        String abortedStep = run.entries.stream().filter(e -> e.startsWith("Abort:")).map(e -> e.substring(6)).findFirst().orElse(null);
        if (state == State.SUCCEEDED || state == State.ROLLED_BACK) {
            checkNetEffects(saga, state, net(world, saga), abortedStep, problems);
        }
        if (state == State.UNFINISHED && !run.hung) {
            // forward recovery once the fault is gone
            List<String> endedBefore = steps(saga).stream().filter(step -> lastOf(run.entries, step).equals("End")).collect(Collectors.toList());
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

    /**
     * se-2: once a saga reports completion, no walk of it writes to the log any more.
     */
    static void checkNothingWrittenAfterCompletion(Run run, List<String> problems) {
        if (run.entriesAtOnComplete != null && !run.entriesAtOnComplete.equals(run.entries)) {
            problems.add("written after completion: onComplete saw " + run.entriesAtOnComplete + ", the log then held " + run.entries);
        }
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
            discard(log);
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
            checkNothingWrittenAfterCompletion(run, problems);
            if (!run.hung && run.failure == null) problems.add("saga succeeded");
            State state = state(saga, run.entries, problems);
            if (state == State.ROLLED_BACK) problems.add("reported ROLLED_BACK although compensation of " + step + " failed");
            if (state == State.UNFINISHED && !run.hung) {
                // forward recovery reruns every step whose effect may be undone, so all effects end up in place
                Run recovered = execute(saga, world, log, run.executionId, true);
                List<String> rp = new ArrayList<>();
                State after = state(saga, recovered.entries, rp);
                if (recovered.failure != null) problems.add("recovery failed with " + describe(recovered.failure));
                if (after != State.SUCCEEDED) problems.add("after recovery " + after + " " + rp + " " + recovered.entries);
                else checkNetEffects(saga, after, net(world, saga), null, problems);
            }
            if (!problems.isEmpty()) {
                problems.add(0, where + " run " + i + " -> " + state + " failed with " + (run.failure == null ? "-" : describe(run.failure)) + " " + run.entries);
            }
            violations.addAll(problems);
            discard(log);
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
     * Finding M1: a crash during the rollback, then forward recovery. A step whose last entry is CompStart or Comp
     * (its effect may be undone) is run again, so the saga ends with every step's effect in place.
     */
    @Test(dataProvider = "crashes")
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
                boolean rollbackStarted = prefix.stream().anyMatch(e -> e.getEntryType() == SagaLogEntryType.CompStart
                        || e.getEntryType() == SagaLogEntryType.Comp);
                if (rollbackStarted != duringRollback) {
                    continue; // crashDuringRollback covers the cuts inside the rollback
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
                        // effect left by the run before the crash, by the cut log: ended, then possibly undone by a
                        // compensation that started (a compensation that started may or may not have finished)
                        boolean undone = cutEntries.contains("CompStart:" + step) || cutEntries.contains("Comp:" + step);
                        long before = cutEntries.contains("End:" + step) && !undone ? 1 : 0;
                        net.merge(step, before, Long::sum);
                    }
                    checkNetEffects(saga, state, net, null, problems);
                }
                for (String step : steps) {
                    if (lastOf(cutEntries, step).equals("End") && after.count("do:" + step) > 0) {
                        problems.add("recovery ran " + step + " again although it had ended");
                    }
                }
                discard(log);
                if (!problems.isEmpty()) {
                    problems.add(0, shape + "/" + runKind + " crash after " + cutEntries + " run " + i + " -> " + recovered.entries);
                    violations.addAll(problems);
                    break;
                }
            }
            discard(full);
        }
        report(violations);
    }

    // ---- fm-2: faults inside the rollback and in the executor -------------------------------------------------

    @DataProvider(name = "snapshotReadFaults")
    public Object[][] snapshotReadFaults() {
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            for (Kind kind : new Kind[]{Kind.RUNTIME, Kind.ERROR}) {
                rows.add(new Object[]{shape, kind});
            }
        }
        return rows.toArray(new Object[0][]);
    }

    /**
     * se-3: the last step aborts, and the rollback's read of the log's entries fails. The saga fails with the read's
     * failure, the abort suppressed on it, as when a compensating action fails; forward recovery then completes it.
     */
    @Test(dataProvider = "snapshotReadFaults")
    public void snapshotReadFault(String shape, Kind kind) {
        Saga saga = SHAPES.get(shape);
        List<String> steps = steps(saga);
        String aborting = steps.get(steps.size() - 1);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            World world = new World();
            FaultySagaLog log = newLog();
            String where = shape + "/abort at " + aborting + "/snapshot read/" + kind;
            Throwable abort = newFault(Kind.ABORT, where);
            Throwable injected = newFault(kind, where);
            world.actionFaultBefore.put(aborting, abort);
            log.failReadOnceAfter(SagaLogEntryType.Abort, injected);
            Run run = execute(saga, world, log, UUID.randomUUID().toString(), false);
            List<String> problems = check(saga, world, log, run, injected, where + " run " + i);
            if (problems.isEmpty() && !Arrays.asList(injected.getSuppressed()).contains(abort)) {
                problems.add(where + " run " + i + ": the abort is not suppressed on the read failure");
            }
            violations.addAll(problems);
            discard(log);
        }
        report(violations);
    }

    enum Saturation {
        /**
         * the pool's one thread is busy when the saga starts: its first walk is rejected
         */
        FIRST_WALK,
        /**
         * the pool's one thread runs the saga: every further walk, and a rollback's walk, is rejected
         */
        LATER_WALK
    }

    @DataProvider(name = "rejections")
    public Object[][] rejections() {
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            for (String runKind : new String[]{"success", "abort"}) {
                for (Saturation saturation : Saturation.values()) {
                    rows.add(new Object[]{shape, runKind, saturation});
                }
            }
        }
        return rows.toArray(new Object[0][]);
    }

    static boolean isRejection(Throwable t) {
        return t instanceof RejectedExecutionException;
    }

    /**
     * se-4: the executor rejects a walk (one thread, no queue, AbortPolicy). The saga ends, with a defined outcome,
     * calls onComplete once and leaves no thread busy; a rejected saga fails with the RejectedExecutionException and
     * forward recovery completes it.
     */
    @Test(dataProvider = "rejections")
    public void rejectedSubmit(String shape, String runKind, Saturation saturation) {
        Saga saga = SHAPES.get(shape);
        List<String> steps = steps(saga);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            SelectableThreadPoolExectutor small = new SelectableThreadPoolExectutor(1, 1, 60, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
            CountDownLatch release = new CountDownLatch(1);
            try {
                if (saturation == Saturation.FIRST_WALK) {
                    small.submit(() -> {
                        release.await();
                        return null;
                    });
                }
                World world = new World();
                FaultySagaLog log = newLog();
                String where = shape + "/" + runKind + "/rejected " + saturation;
                Throwable abort = runKind.equals("abort") ? newFault(Kind.ABORT, where) : null;
                if (abort != null) {
                    world.actionFaultBefore.put(steps.get(steps.size() - 1), abort);
                }
                Run run = start(small, saga, world, log, UUID.randomUUID().toString(), false);
                awaitCompletion(run, 5_000);
                release.countDown();
                finish(small, log, run);
                Predicate<Throwable> accepted = saturation == Saturation.FIRST_WALK
                        ? SagaFaultMatrixTest::isRejection
                        : f -> f == null && abort == null || f == abort || isRejection(f);
                violations.addAll(check(saga, world, log, run, accepted,
                        saturation == Saturation.FIRST_WALK ? "RejectedExecutionException" : "success, the abort or RejectedExecutionException",
                        where + " run " + i));
                discard(log);
            } finally {
                release.countDown();
                small.shutdownNow();
            }
        }
        report(violations);
    }

    @DataProvider(name = "shutdowns")
    public Object[][] shutdowns() {
        List<Object[]> rows = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            for (String runKind : new String[]{"success", "abort"}) {
                rows.add(new Object[]{shape, runKind});
            }
        }
        return rows.toArray(new Object[0][]);
    }

    /**
     * se-4: the executor is shut down with {@code shutdownNow()} at a random point of a saga, and the tasks it returns
     * are cancelled, as the documentation says. The saga ends, calls onComplete once, and writes nothing after its
     * completion; no thread of the executor stays busy; an unfinished saga is completed by forward recovery.
     */
    @Test(dataProvider = "shutdowns")
    public void shutdownNowDuringSaga(String shape, String runKind) throws InterruptedException {
        Saga saga = SHAPES.get(shape);
        List<String> steps = steps(saga);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            SelectableThreadPoolExectutor own = new SelectableThreadPoolExectutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            World world = new World();
            FaultySagaLog log = newLog();
            for (String step : steps) {
                world.delayMs.put(step, 40L);
            }
            String where = shape + "/" + runKind + "/shutdownNow";
            if (runKind.equals("abort")) {
                world.actionFaultBefore.put(steps.get(steps.size() - 1), newFault(Kind.ABORT, where));
            }
            Run run = start(own, saga, world, log, UUID.randomUUID().toString(), false);
            sleep(ThreadLocalRandom.current().nextLong(0, 40L * (steps.size() + 1)));
            for (Runnable dropped : own.shutdownNow()) {
                if (dropped instanceof Future) {
                    ((Future<?>) dropped).cancel(false);
                }
            }
            awaitCompletion(run, 5_000);
            List<String> problems = new ArrayList<>();
            if (!own.awaitTermination(2, TimeUnit.SECONDS)) {
                problems.add(where + " run " + i + ": the executor's threads did not end after shutdownNow");
            }
            finish(own, log, run);
            world.delayMs.clear();
            problems.addAll(check(saga, world, log, run, f -> true, "any outcome", where + " run " + i));
            violations.addAll(problems);
            discard(log);
        }
        report(violations);
    }

    // ---- fm-4: many sagas on a small pool ---------------------------------------------------------------------

    static final int LOAD_SAGAS = Integer.getInteger("matrix.load.sagas", 16);
    static final int LOAD_THREADS = Integer.getInteger("matrix.load.threads", 6);

    enum Overload {ABORT_POLICY, CALLER_RUNS_POLICY}

    @DataProvider(name = "load")
    public Object[][] load() {
        return new Object[][]{{Overload.ABORT_POLICY}, {Overload.CALLER_RUNS_POLICY}};
    }

    /**
     * fm-4: {@value #LOAD_SAGAS} sagas of every shape, started at about the same time from four threads, on a pool of
     * {@value #LOAD_THREADS} threads without a queue, with a fault in most of them. Every saga ends with a defined
     * outcome, calls onComplete once, and no thread stays busy; a saga the pool could not take fails with
     * RejectedExecutionException and is completed by forward recovery. A queued pool smaller than the sagas' waiting
     * walks starves by design (limitations.md, "Thread starvation"), so the pool sheds load instead. With
     * CallerRunsPolicy (se-5), a walk the pool would run on the submitting thread is rejected instead.
     */
    @Test(dataProvider = "load")
    public void load(Overload overload) throws Exception {
        List<String> shapes = new ArrayList<>(SHAPES.keySet());
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < REPEAT && violations.isEmpty(); i++) {
            SelectableThreadPoolExectutor small = new SelectableThreadPoolExectutor(LOAD_THREADS, LOAD_THREADS, 60, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    overload == Overload.ABORT_POLICY ? new ThreadPoolExecutor.AbortPolicy() : new ThreadPoolExecutor.CallerRunsPolicy());
            ExecutorService drivers = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "load-driver");
                t.setDaemon(true);
                return t;
            });
            try {
                Random random = new Random(i);
                List<Saga> sagas = new ArrayList<>();
                List<World> worlds = new ArrayList<>();
                List<FaultySagaLog> logs = new ArrayList<>();
                List<Throwable> injected = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                List<Future<Run>> started = new ArrayList<>();
                for (int k = 0; k < LOAD_SAGAS; k++) {
                    String shape = shapes.get(k % shapes.size());
                    Saga saga = SHAPES.get(shape);
                    List<String> steps = steps(saga);
                    World world = new World();
                    for (String step : steps) {
                        world.delayMs.put(step, (long) random.nextInt(15));
                    }
                    String step = steps.get(random.nextInt(steps.size()));
                    Kind kind = k % 4 == 3 ? null : Kind.values()[k % 3];
                    String label = overload + "/" + shape + "/" + (kind == null ? "no fault" : step + "/" + kind) + " saga " + k + " run " + i;
                    Throwable fault = kind == null ? null : newFault(kind, label);
                    FaultySagaLog log = newLog();
                    if (fault != null) {
                        if (random.nextBoolean()) {
                            world.actionFaultBefore.put(step, fault);
                        } else if (kind == Kind.ABORT) {
                            world.actionFaultAfter.put(step, fault);
                        } else {
                            log.failOnce(SagaLogEntryType.End, step, fault);
                        }
                    }
                    sagas.add(saga);
                    worlds.add(world);
                    logs.add(log);
                    injected.add(fault);
                    labels.add(label);
                    long stagger = random.nextInt(20);
                    started.add(drivers.submit(() -> {
                        sleep(stagger);
                        return start(small, saga, world, log, UUID.randomUUID().toString(), false);
                    }));
                }
                List<Run> runs = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 10_000;
                for (int k = 0; k < LOAD_SAGAS; k++) {
                    Run run;
                    try {
                        run = started.get(k).get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        awaitCompletion(run, Math.max(1, deadline - System.currentTimeMillis()));
                    } catch (TimeoutException e) {
                        run = null; // executeSaga itself never returned
                    }
                    runs.add(run);
                }
                for (int k = 0; k < LOAD_SAGAS; k++) {
                    Run run = runs.get(k);
                    if (run == null) {
                        violations.add(labels.get(k) + ": executeSaga did not return within 10 s");
                        continue;
                    }
                    finish(small, logs.get(k), run);
                    Throwable fault = injected.get(k);
                    worlds.get(k).delayMs.clear();
                    violations.addAll(check(sagas.get(k), worlds.get(k), logs.get(k), run,
                            f -> f == null ? fault == null : f == fault || isRejection(f),
                            "the injected fault or RejectedExecutionException", labels.get(k)));
                    discard(logs.get(k));
                }
                small.shutdown();
                if (!small.awaitTermination(2, TimeUnit.SECONDS)) {
                    violations.add(overload + " run " + i + ": pool threads still busy after shutdown");
                }
            } finally {
                small.shutdownNow();
                drivers.shutdownNow();
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
