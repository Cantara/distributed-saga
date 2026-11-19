package no.cantara.saga.samples.polyglot;

import io.undertow.Undertow;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sample server. The saga-log lifecycle:
 * <ol>
 * <li>at start-up, a fixed set of instance-local saga logs is registered;</li>
 * <li>before the server starts, recovery completes the unfinished sagas in those logs;</li>
 * <li>per request, {@link PolyglotHttpHandler} acquires a free log with {@code tryAcquire(owner, timeout)}, runs the
 * saga, truncates the execution's entries when it has finished, and releases the log with
 * {@code releaseOwnership(owner, logId)}.</li>
 * </ol>
 */
@SuppressWarnings("deprecation") // SagaExecution takes the executor under its old name
public class PolyglotMain {

    static final String SAGA_LOG_NAME_PREFIX = "polyglot-saga-log-";
    static final String SAGA_LOGS_PROPERTY = "polyglot.saga-logs";
    static final String DEFAULT_SAGA_LOGS = "4";

    final SelectableThreadPoolExecutor pool;
    final SagaLogPool sagaLogPool;
    /**
     * The saga logs of this instance, registered at start-up. Their number limits how many sagas run at once.
     */
    final Set<SagaLogId> sagaLogIds;
    final AdapterLoader adapterLoader;
    final Undertow server;
    private final List<String> recoveredExecutionIds = new ArrayList<>();

    PolyglotMain(Map<String, String> configuration) {
        this(configuration, PolyglotHttpHandler.adapters());
    }

    PolyglotMain(Map<String, String> configuration, AdapterLoader adapterLoader) {
        String host = configuration.get("host");
        int port = Integer.parseInt(configuration.get("port"));
        this.adapterLoader = adapterLoader;

        AtomicLong nextWorkerId = new AtomicLong(1);
        pool = new SelectableThreadPoolExecutor(
                5, 100,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("sec-worker" + nextWorkerId.getAndIncrement());
                    thread.setUncaughtExceptionHandler((t, e) -> {
                        System.err.println("Uncaught exception in thread " + thread.getName());
                        e.printStackTrace();
                    });
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        /*
         * Create the saga-log pool, and register a fixed set of logs, one per saga that may run at the same time.
         */
        ServiceLoader<SagaLogInitializer> loader = ServiceLoader.load(SagaLogInitializer.class);
        String sagalogProviderClass = configuration.get("sagalog.provider");
        sagaLogPool = loader.stream().filter(c -> sagalogProviderClass.equals(c.type().getName())).findFirst().orElseThrow().get().initialize(configuration);
        int sagaLogs = Integer.parseInt(configuration.getOrDefault(SAGA_LOGS_PROPERTY, DEFAULT_SAGA_LOGS));
        Set<SagaLogId> ids = new LinkedHashSet<>();
        for (int i = 0; i < sagaLogs; i++) {
            ids.add(sagaLogPool.registerInstanceLocalIdFor(SAGA_LOG_NAME_PREFIX + i));
        }
        sagaLogIds = Collections.unmodifiableSet(ids);

        server = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(new PolyglotHttpHandler(pool, sagaLogPool, adapterLoader))
                .build();
    }

    /**
     * Completes the unfinished sagas of this instance's logs, one log at a time, before any request is served.
     */
    private void recover() {
        SagaLogOwner owner = new SagaLogOwner("polyglot-recovery");
        for (SagaLogId logId : sagaLogIds) {
            SagaLog sagaLog = sagaLogPool.tryTakeOwnership(owner, logId);
            if (sagaLog == null) {
                throw new IllegalStateException("Saga log " + logId + " is owned by someone else at start-up");
            }
            try {
                recoveredExecutionIds.addAll(PolyglotRecovery.recover(sagaLog, pool, PolyglotSaga.SAGAS_BY_NAME, adapterLoader));
            } finally {
                sagaLogPool.releaseOwnership(owner, logId);
            }
        }
    }

    /**
     * @return the ids of the executions that recovery at start-up completed
     */
    List<String> recoveredExecutionIds() {
        return List.copyOf(recoveredExecutionIds);
    }

    int port() {
        return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    private void shutdownPool() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    PolyglotMain start() {
        recover();
        if (!recoveredExecutionIds.isEmpty()) {
            System.out.format("Recovered %d saga execution(s)%n", recoveredExecutionIds.size());
        }
        server.start();
        System.out.format("Server started, listening on %s:%d%n",
                ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getHostString(),
                port());
        return this;
    }

    void stop() {
        server.stop();
        shutdownPool();
        sagaLogPool.shutdown();
        System.out.println("Server shut down");
    }

    public static void main(String[] args) {
        Map<String, String> configuration = Map.of(
                "host", "127.0.0.1",
                "port", "8139",
                "sagalog.provider", "no.cantara.sagalog.file.FileSagaLogInitializer",
                "filesagalog.folder", "./target/sagalog",
                "cluster.instance-id", "01"
        );
        PolyglotMain polyglotMain = new PolyglotMain(configuration);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> polyglotMain.stop()));
        polyglotMain.start();
    }
}
