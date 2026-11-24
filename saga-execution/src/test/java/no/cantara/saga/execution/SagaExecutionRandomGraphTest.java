package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.ActionOutputSerializer;
import no.cantara.saga.execution.adapter.ActionOutputSerializerToStringAndStringConstructor;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.execution.adapter.AsyncSagaAdapter;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogId;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Random sagas on small pools: random graphs of up to eight steps, pools of one to three threads, up to four sagas at
 * once, and each saga succeeding, aborting at a random step, or failing at one. Each step is synchronous or, at random,
 * asynchronous: its stage completes on a thread of another executor, as an RxJava scheduler or a database driver
 * would. Every run checks:
 * <ul>
 * <li>no saga-log write, and no {@code onComplete}, runs on that other executor's threads: the saga continues on its
 * own pool;</li>
 * <li>the saga completes: no saga waits for a pool thread another walk of its own, or another saga, holds;</li>
 * <li>each step runs at most once, only after every step it depends on has ended, and receives their outputs;</li>
 * <li>a successful saga runs every step;</li>
 * <li>an aborted saga compensates exactly the steps that ended, each one only after the steps that depend on it;</li>
 * <li>a saga that failed compensates nothing, and fails with the step's exception.</li>
 * </ul>
 * The graph and the outcome come from the seed, so a failure names the case to run again; the interleaving does not.
 */
public class SagaExecutionRandomGraphTest {

    private static final int CASES = Integer.getInteger("saga.randomGraph.cases", 300);

    enum Outcome {SUCCESS, ABORT, FAIL}

    @DataProvider(name = "cases", parallel = false)
    public Iterator<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (int seed = 0; seed < CASES; seed++) {
            cases.add(new Object[]{seed});
        }
        return cases.iterator();
    }

    /**
     * One saga execution: what its steps did, in the order of a counter shared by all executions of the case.
     */
    static final class Run {
        final AtomicLong clock;
        final String failAt;
        final Outcome outcome;
        final RuntimeException failure;
        final ConcurrentMap<String, Long> started = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Long> ended = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Long> compensated = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Set<String>> dependees = new ConcurrentHashMap<>();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        Run(AtomicLong clock, Outcome outcome, String failAt) {
            this.clock = clock;
            this.outcome = outcome;
            this.failAt = failAt;
            this.failure = new IllegalStateException("step " + failAt + " failed");
        }

        @Override
        public String toString() {
            return "run"; // the saga input, written to the log by the start adapter
        }
    }

    @Test(dataProvider = "cases")
    public void randomSaga(int seed) {
        Random random = new Random(seed);
        int steps = 1 + random.nextInt(8);
        Saga saga = randomSaga(random, steps);
        List<String> stepIds = saga.nodes().stream().map(n -> n.id)
                .filter(id -> !Saga.ID_START.equals(id) && !Saga.ID_END.equals(id)).collect(Collectors.toList());
        int threads = 1 + random.nextInt(3);
        int sagas = 1 + random.nextInt(4);

        ExecutorService foreign = Executors.newFixedThreadPool(2, r -> new Thread(r, "foreign-" + FOREIGN_THREADS.incrementAndGet()));
        AdapterLoader adapters = new AdapterLoader();
        for (String id : stepIds) {
            BiFunction<Object, Map<SagaNode, Object>, String> action = (input, dependeesOutput) -> {
                Run run = (Run) input;
                run.started.put(id, run.clock.incrementAndGet());
                run.dependees.put(id, dependeesOutput.keySet().stream().map(n -> n.id).collect(Collectors.toSet()));
                for (Map.Entry<SagaNode, Object> e : dependeesOutput.entrySet()) {
                    Object expected = Saga.ID_START.equals(e.getKey().id) ? null : e.getKey().id;
                    if (expected == null ? e.getValue() != null : !expected.equals(e.getValue())) {
                        run.errors.add(id + " received " + e.getValue() + " as the output of " + e.getKey().id);
                    }
                }
                if (random(id).nextBoolean()) {
                    Thread.yield();
                }
                if (id.equals(run.failAt) && run.outcome == Outcome.ABORT) {
                    throw new AbortSagaException("abort at " + id);
                }
                if (id.equals(run.failAt) && run.outcome == Outcome.FAIL) {
                    throw run.failure;
                }
                run.ended.put(id, run.clock.incrementAndGet());
                return id;
            };
            BiConsumer<Object, String> compensation = (input, output) -> {
                Run run = (Run) input;
                if (!id.equals(output)) {
                    run.errors.add(id + " compensated with output " + output);
                }
                if (run.compensated.put(id, run.clock.incrementAndGet()) != null) {
                    run.errors.add(id + " compensated twice");
                }
            };
            if (random.nextBoolean()) {
                adapters.register(new AsyncStep(id, foreign, action, compensation));
            } else {
                adapters.register(new Adapter<String>(String.class, id, action, compensation));
            }
        }

        SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        String context = "seed " + seed + ", " + threads + " thread(s), " + sagas + " saga(s), " + saga;
        try {
            AtomicLong clock = new AtomicLong();
            List<Run> runs = new ArrayList<>();
            List<SagaHandoffControl> controls = new ArrayList<>();
            for (int i = 0; i < sagas; i++) {
                Outcome outcome = Outcome.values()[random.nextInt(Outcome.values().length)];
                Run run = new Run(clock, outcome, stepIds.get(random.nextInt(stepIds.size())));
                runs.add(run);
                controls.add(new SagaExecution(new NoWritesOnForeignThreads(MemoryLogs.newLog(), run), pool, saga, adapters)
                        .executeSaga(UUID.randomUUID().toString(), run, false, r -> {
                            if (Thread.currentThread().getName().startsWith("foreign-")) {
                                run.errors.add("onComplete ran on " + Thread.currentThread().getName());
                            }
                        }));
            }
            for (int i = 0; i < sagas; i++) {
                check(saga, runs.get(i), controls.get(i), context + ", saga " + i + " " + runs.get(i).outcome + " at " + runs.get(i).failAt);
            }
        } finally {
            pool.shutdownNow();
            foreign.shutdownNow();
        }
    }

    private static final AtomicInteger FOREIGN_THREADS = new AtomicInteger();

    /**
     * An asynchronous step: the action and the compensation run on the other executor, and the stage completes there.
     */
    static final class AsyncStep implements AsyncSagaAdapter<String> {
        final String id;
        final ExecutorService foreign;
        final BiFunction<Object, Map<SagaNode, Object>, String> action;
        final BiConsumer<Object, String> compensation;

        AsyncStep(String id, ExecutorService foreign, BiFunction<Object, Map<SagaNode, Object>, String> action, BiConsumer<Object, String> compensation) {
            this.id = id;
            this.foreign = foreign;
            this.action = action;
            this.compensation = compensation;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public CompletionStage<String> executeActionAsync(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
            return CompletableFuture.supplyAsync(() -> action.apply(sagaInput, dependeesOutput), foreign);
        }

        @Override
        public CompletionStage<Void> executeCompensatingActionAsync(SagaNode sagaNode, Object sagaInput, String actionOutput) {
            return CompletableFuture.runAsync(() -> compensation.accept(sagaInput, actionOutput), foreign);
        }

        @Override
        public boolean hasCompensatingAction() {
            return true;
        }

        @Override
        public ActionOutputSerializer<String> serializer() {
            return new ActionOutputSerializerToStringAndStringConstructor<>(String.class);
        }
    }

    /**
     * A saga log that records an error when it is written on a thread of the other executor.
     */
    static final class NoWritesOnForeignThreads implements SagaLog {
        final SagaLog delegate;
        final Run run;

        NoWritesOnForeignThreads(SagaLog delegate, Run run) {
            this.delegate = delegate;
            this.run = run;
        }

        @Override
        public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
            if (Thread.currentThread().getName().startsWith("foreign-")) {
                run.errors.add(builder.entryType() + " written on " + Thread.currentThread().getName());
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

    private static Random random(String id) {
        return new Random(Thread.currentThread().getId() ^ id.hashCode() ^ System.nanoTime());
    }

    private static void check(Saga saga, Run run, SagaHandoffControl control, String context) {
        Throwable failure = null;
        try {
            control.getCompletionFuture().copy().orTimeout(20, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            failure = e.getCause();
        }
        if (failure instanceof TimeoutException) {
            fail("the saga did not complete within 20 s: " + context);
        }
        assertTrue(run.errors.isEmpty(), run.errors + ": " + context);

        Set<String> ran = run.started.keySet();
        for (String id : ran) {
            SagaNode node = node(saga, id);
            Set<String> parents = node.incoming.stream().map(n -> n.id).collect(Collectors.toSet());
            assertEquals(run.dependees.get(id), parents, "the outputs " + id + " received: " + context);
            for (String parent : parents) {
                if (Saga.ID_START.equals(parent)) {
                    continue;
                }
                Long parentEnded = run.ended.get(parent);
                assertNotNull(parentEnded, id + " started before its dependency " + parent + " ended: " + context);
                assertTrue(parentEnded < run.started.get(id), id + " started before " + parent + " ended: " + context);
            }
        }

        switch (run.outcome) {
            case SUCCESS -> {
                assertEquals(failure, null, "a successful saga completes normally: " + context);
                assertEquals(run.ended.keySet(), steps(saga), "a successful saga runs every step: " + context);
                assertTrue(run.compensated.isEmpty(), "a successful saga compensates nothing: " + context);
            }
            case ABORT -> {
                assertTrue(failure instanceof AbortSagaException, "an aborted saga fails with its abort, not " + failure + ": " + context);
                assertEquals(run.compensated.keySet(), run.ended.keySet(), "the rollback compensates exactly the steps that ended: " + context);
                for (String id : run.compensated.keySet()) {
                    for (SagaNode child : node(saga, id).outgoing) {
                        Long childCompensated = run.compensated.get(child.id);
                        if (childCompensated != null) {
                            assertTrue(childCompensated < run.compensated.get(id),
                                    id + " compensated before " + child.id + ", which depends on it: " + context);
                        }
                    }
                }
            }
            case FAIL -> {
                assertSame(failure, run.failure, "a failed saga fails with the step's exception: " + context);
                assertTrue(run.compensated.isEmpty(), "a failed saga compensates nothing: " + context);
            }
        }
    }

    private static Set<String> steps(Saga saga) {
        return saga.nodes().stream().map(n -> n.id)
                .filter(id -> !Saga.ID_START.equals(id) && !Saga.ID_END.equals(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static SagaNode node(Saga saga, String id) {
        return saga.nodes().stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow();
    }

    /**
     * A random graph: steps n0 .. n(steps-1), each linked to a random set of later steps or to E; S links to every step
     * nothing else links to.
     */
    private static Saga randomSaga(Random random, int steps) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        Set<String> linkedTo = new LinkedHashSet<>();
        for (int i = 0; i < steps; i++) {
            List<String> targets = new ArrayList<>();
            for (int j = i + 1; j < steps; j++) {
                if (random.nextInt(3) == 0) {
                    targets.add("n" + j);
                }
            }
            if (targets.isEmpty() || random.nextInt(4) == 0) {
                targets.add(Saga.ID_END);
            }
            outgoing.put("n" + i, targets);
            linkedTo.addAll(targets);
        }
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            if (!linkedTo.contains("n" + i)) {
                roots.add("n" + i);
            }
        }
        Saga.SagaBuilder builder = Saga.start("random").linkTo(roots.toArray(new String[0]));
        for (Map.Entry<String, List<String>> e : outgoing.entrySet()) {
            builder.id(e.getKey()).adapter(e.getKey()).linkTo(e.getValue().toArray(new String[0]));
        }
        return builder.end();
    }
}
