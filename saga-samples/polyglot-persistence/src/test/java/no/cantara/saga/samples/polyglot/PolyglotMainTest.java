package no.cantara.saga.samples.polyglot;

import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.samples.polyglot.adapter.PublishToPubSub;
import no.cantara.saga.samples.polyglot.adapter.WriteToGraph;
import no.cantara.saga.samples.polyglot.adapter.WriteToObjectStore;
import no.cantara.saga.samples.polyglot.adapter.WriteToRDBMS;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.json.JSONObject;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class PolyglotMainTest {

    private static final String FILE_PROVIDER = "no.cantara.sagalog.file.FileSagaLogInitializer";

    private static Map<String, String> configuration(String folder) {
        return Map.of(
                "host", "127.0.0.1",
                "port", "0",
                "sagalog.provider", FILE_PROVIDER,
                "filesagalog.folder", "target/" + folder + "-" + UUID.randomUUID(),
                "cluster.instance-id", "standalone"
        );
    }

    @Test
    public void thatCoordinatorCanExecuteSaga() throws IOException {
        PolyglotMain polyglotMain = new PolyglotMain(configuration("sagalogtest")).start();
        try {
            Response response = put(polyglotMain, "/test1", "Hello Polyglot");
            assertEquals(response.status, 201, response.body);
            JSONObject json = new JSONObject(response.body);
            System.out.println(json.toString(2));
            assertEquals(json.getJSONArray("log").length(), 10, "Start and End of S, the four steps and E");
        } finally {
            polyglotMain.stop();
        }
    }

    @Test
    public void thatTheLogsAreTruncatedAndReusedAcrossRequests() throws IOException {
        PolyglotMain polyglotMain = new PolyglotMain(configuration("sagalogtest-reuse")).start();
        try {
            for (int i = 0; i < 5; i++) {
                Response response = put(polyglotMain, "/reuse" + i, "Hello " + i);
                assertEquals(response.status, 201, response.body);
                assertEquals(new JSONObject(response.body).getJSONArray("log").length(), 10);
            }
            assertEquals(polyglotMain.sagaLogPool.instanceLocalLogIds(), polyglotMain.sagaLogIds,
                    "only the logs registered at start-up are used");
            for (SagaLogId logId : polyglotMain.sagaLogIds) {
                assertEquals(entriesOf(polyglotMain.sagaLogPool, logId).size(), 0, "log " + logId + " is truncated after each saga");
            }
        } finally {
            polyglotMain.stop();
        }
    }

    @Test
    public void thatAnUnfinishedSagaIsRecoveredAtStartUp() throws IOException {
        Map<String, String> configuration = configuration("sagalogtest-recovery");
        String executionId = UUID.randomUUID().toString();

        /*
         * A crash: the saga started, rdbms ended, and nothing else was written.
         */
        SagaLogPool crashedPool = sagaLogPool(configuration);
        SagaLogId logId = crashedPool.registerInstanceLocalIdFor(PolyglotMain.SAGA_LOG_NAME_PREFIX + 0);
        SagaLogOwner crashed = new SagaLogOwner("crashed");
        SagaLog crashedLog = crashedPool.tryTakeOwnership(crashed, logId);
        JSONObject input = new JSONObject().put("resource-path", "/crashed").put("data", "Hello crash");
        crashedLog.write(crashedLog.builder().startSaga(executionId, PolyglotSaga.NAME, input.toString())).join();
        crashedLog.write(crashedLog.builder().startAction(executionId, "rdbms")).join();
        crashedLog.write(crashedLog.builder().endAction(executionId, "rdbms", new JSONObject().put("logged", true).toString())).join();
        crashedPool.releaseOwnership(crashed, logId);
        crashedPool.shutdown();

        AtomicInteger rdbmsRuns = new AtomicInteger();
        AtomicInteger pubsubRuns = new AtomicInteger();
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(JSONObject.class, WriteToRDBMS.NAME, (in, out) -> {
                    rdbmsRuns.incrementAndGet();
                    return new JSONObject();
                }))
                .register(new WriteToGraph(JSONObject.class))
                .register(new WriteToObjectStore(JSONObject.class))
                .register(new Adapter<>(JSONObject.class, PublishToPubSub.NAME, (in, out) -> {
                    pubsubRuns.incrementAndGet();
                    return new JSONObject();
                }));

        PolyglotMain polyglotMain = new PolyglotMain(configuration, adapters).start();
        try {
            assertEquals(polyglotMain.recoveredExecutionIds(), List.of(executionId));
            assertEquals(rdbmsRuns.get(), 0, "a step that ended is not run again");
            assertEquals(pubsubRuns.get(), 1, "the rest of the saga runs");
            for (SagaLogId id : polyglotMain.sagaLogIds) {
                assertEquals(entriesOf(polyglotMain.sagaLogPool, id).size(), 0, "log " + id + " is truncated after recovery");
            }
        } finally {
            polyglotMain.stop();
        }
    }

    @Test
    public void thatARolledBackSagaGets409WithTheExecutionId() throws IOException {
        AdapterLoader adapters = new AdapterLoader()
                .register(new Adapter<>(JSONObject.class, WriteToRDBMS.NAME, (in, out) -> {
                    throw new AbortSagaException("rdbms said no");
                }))
                .register(new WriteToGraph(JSONObject.class))
                .register(new WriteToObjectStore(JSONObject.class))
                .register(new PublishToPubSub(JSONObject.class));
        PolyglotMain polyglotMain = new PolyglotMain(configuration("sagalogtest-abort"), adapters).start();
        try {
            Response response = put(polyglotMain, "/abort", "Hello abort");
            assertEquals(response.status, 409, response.body);
            JSONObject json = new JSONObject(response.body);
            assertNotNull(json.getString("executionId"));
            assertTrue(json.getString("error").contains("rdbms said no"), json.toString());
            for (SagaLogId id : polyglotMain.sagaLogIds) {
                assertEquals(entriesOf(polyglotMain.sagaLogPool, id).size(), 0, "a rolled-back saga is finished, and its log truncated");
            }
        } finally {
            polyglotMain.stop();
        }
    }

    @Test
    public void thatAFailedSagaGets500WithTheExecutionId() throws IOException {
        AdapterLoader adapters = new AdapterLoader()
                .register(new WriteToRDBMS(JSONObject.class))
                .register(new WriteToGraph(JSONObject.class))
                .register(new WriteToObjectStore(JSONObject.class))
                .register(new Adapter<>(JSONObject.class, PublishToPubSub.NAME, (in, out) -> {
                    throw new IllegalStateException("pubsub is down");
                }));
        PolyglotMain polyglotMain = new PolyglotMain(configuration("sagalogtest-fail"), adapters).start();
        try {
            Response response = put(polyglotMain, "/fail", "Hello failure");
            assertEquals(response.status, 500, response.body);
            JSONObject json = new JSONObject(response.body);
            String executionId = json.getString("executionId");
            assertTrue(json.getString("error").contains("pubsub is down"), json.toString());
            // not finished: its entries stay in the log for recovery at the next start-up
            long kept = polyglotMain.sagaLogIds.stream()
                    .mapToLong(id -> entriesOf(polyglotMain.sagaLogPool, id).stream().filter(e -> executionId.equals(e.getExecutionId())).count())
                    .sum();
            assertTrue(kept > 0, "the entries of an unfinished saga are kept");
        } finally {
            polyglotMain.stop();
        }
    }

    private static SagaLogPool sagaLogPool(Map<String, String> configuration) {
        return ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(p -> FILE_PROVIDER.equals(p.type().getName()))
                .findFirst().orElseThrow().get()
                .initialize(configuration);
    }

    private static List<SagaLogEntry> entriesOf(SagaLogPool pool, SagaLogId logId) {
        SagaLogOwner owner = new SagaLogOwner("test");
        SagaLog log = pool.tryTakeOwnership(owner, logId);
        assertNotNull(log, "log " + logId + " is free");
        try {
            return log.readIncompleteSagas().collect(Collectors.toList());
        } finally {
            pool.releaseOwnership(owner, logId);
        }
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response put(PolyglotMain polyglotMain, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + polyglotMain.port() + path).openConnection();
        try {
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = "";
            if (in != null) {
                try (in) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    in.transferTo(bytes);
                    responseBody = bytes.toString(StandardCharsets.UTF_8);
                }
            }
            return new Response(status, responseBody);
        } finally {
            connection.disconnect();
        }
    }
}
