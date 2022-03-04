package no.cantara.saga.samples.polyglot;

import io.undertow.Undertow;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PolyglotMain {

    final SelectableThreadPoolExectutor pool;
    final Undertow server;

    PolyglotMain(Map<String, String> configuration) {
        String host = configuration.get("host");
        int port = Integer.parseInt(configuration.get("port"));

        AtomicLong nextWorkerId = new AtomicLong(1);
        pool = new SelectableThreadPoolExectutor(
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
         * Create saga log.
         */
        ServiceLoader<SagaLogInitializer> loader = ServiceLoader.load(SagaLogInitializer.class);
        String sagalogProviderClass = configuration.get("sagalog.provider");
        SagaLogPool sagaLogPool = loader.stream().filter(c -> sagalogProviderClass.equals(c.type().getName())).findFirst().orElseThrow().get().initialize(configuration);

        server = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(new PolyglotHttpHandler(pool, sagaLogPool))
                .build();
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
        server.start();
        System.out.format("Server started, listening on %s:%d%n",
                ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getHostString(),
                ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort());
        return this;
    }

    void stop() {
        server.stop();
        shutdownPool();
        System.out.println("Server shut down");
    }

    public static void main(String[] args) {
        Map<String, String> configuration = Map.of(
                "host", "127.0.0.1",
                "port", "8139",
                "sagalog.provider", "no.cantara.sagalog.file.FileSagaLogInitializer",
                "filesagalog.folder", "./target/sagalog"
        );
        PolyglotMain polyglotMain = new PolyglotMain(configuration);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> polyglotMain.stop()));
        polyglotMain.start();
    }
}
