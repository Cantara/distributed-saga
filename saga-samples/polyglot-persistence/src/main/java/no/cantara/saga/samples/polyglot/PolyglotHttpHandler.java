package no.cantara.saga.samples.polyglot;

import io.undertow.io.Receiver;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.execution.SagaExecution;
import no.cantara.saga.execution.SagaHandoffControl;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.samples.polyglot.adapter.PublishToPubSub;
import no.cantara.saga.samples.polyglot.adapter.WriteToGraph;
import no.cantara.saga.samples.polyglot.adapter.WriteToObjectStore;
import no.cantara.saga.samples.polyglot.adapter.WriteToRDBMS;
import no.cantara.saga.serialization.SagaSerializer;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the polyglot saga for each PUT request, on a saga log acquired from the pool for that request.
 * <p>
 * Replies 201 when the saga succeeded, 409 when it was rolled back, 500 when it failed otherwise, and 503 when no saga
 * log became free in time. Every saga reply holds the execution id and the log entries of the execution.
 */
@SuppressWarnings("deprecation") // SagaExecution takes the executor under its old name
public class PolyglotHttpHandler implements HttpHandler {

    static final long ACQUIRE_TIMEOUT_SECONDS = 5;

    private final SelectableThreadPoolExectutor executorService;
    private final SagaLogPool sagaLogPool;
    private final AdapterLoader adapterLoader;

    public PolyglotHttpHandler(SelectableThreadPoolExectutor executorService, SagaLogPool sagaLogPool) {
        this(executorService, sagaLogPool, adapters());
    }

    PolyglotHttpHandler(SelectableThreadPoolExectutor executorService, SagaLogPool sagaLogPool, AdapterLoader adapterLoader) {
        this.executorService = executorService;
        this.sagaLogPool = sagaLogPool;
        this.adapterLoader = adapterLoader;
    }

    /**
     * @return all adapters/drivers that are used in the sagas of this sample
     */
    static AdapterLoader adapters() {
        return new AdapterLoader()
                .register(new WriteToRDBMS(JSONObject.class))
                .register(new WriteToGraph(JSONObject.class))
                .register(new WriteToObjectStore(JSONObject.class))
                .register(new PublishToPubSub(JSONObject.class));
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        if (exchange.getRequestMethod().equalToString("get")) {
            return;
        } else if (exchange.getRequestMethod().equalToString("put")) {
            exchange.getRequestReceiver().receiveFullBytes(PUTCallback());
            return;
        }

        send(exchange, 400, "text/plain", "Unsupported method: " + exchange.getRequestMethod());
    }

    private Receiver.FullBytesCallback PUTCallback() {
        return (exchange, buf) -> {

            /*
             * Prepare Saga input
             */
            JSONObject inputRoot = new JSONObject();
            inputRoot.put("resource-path", exchange.getRequestPath());
            inputRoot.put("data", new String(buf, StandardCharsets.UTF_8));

            /*
             * Acquire a free saga log for this execution
             */
            String executionId = UUID.randomUUID().toString();
            SagaLogOwner owner = new SagaLogOwner(executionId);
            SagaLog sagaLog;
            try {
                sagaLog = sagaLogPool.tryAcquire(owner, ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 503, "text/plain", "Interrupted while waiting for a saga log");
                return;
            }
            if (sagaLog == null) {
                send(exchange, 503, "text/plain", "No saga log became free within " + ACQUIRE_TIMEOUT_SECONDS + " seconds, try again later");
                return;
            }

            int statusCode;
            String responseBody;
            try {
                /*
                 * Execute Saga, and wait for it
                 */
                SagaExecution sagaExecution = new SagaExecution(sagaLog, executorService, PolyglotSaga.SAGA, adapterLoader);
                long executionStartTime = System.currentTimeMillis();
                long handoffTime = -1;
                Throwable failure = null;
                try {
                    SagaHandoffControl handoffControl = sagaExecution.executeSaga(executionId, inputRoot, false, r -> {
                    });
                    handoffControl.getHandoffFuture().join(); // wait for saga handoff
                    handoffTime = System.currentTimeMillis() - executionStartTime;
                    handoffControl.getCompletionFuture().join();
                } catch (CompletionException e) {
                    failure = e.getCause() != null ? e.getCause() : e;
                }
                long executionTime = System.currentTimeMillis() - executionStartTime;

                List<SagaLogEntry> sagaLogEntries = sagaLog.readEntries(executionId).collect(Collectors.toList());
                if (failure == null) {
                    statusCode = 201;
                } else if (failure instanceof AbortSagaException) {
                    statusCode = 409; // rolled back
                } else {
                    statusCode = 500;
                }
                responseBody = responseJson(executionId, handoffTime, executionTime, failure, sagaLog, sagaLogEntries);

                truncateIfFinished(sagaLog, executionId, sagaLogEntries);
            } finally {
                sagaLogPool.releaseOwnership(owner, sagaLog.id());
            }

            send(exchange, statusCode, "application/json; charset=utf-8", responseBody);
        };
    }

    /**
     * Removes the entries of a finished execution (succeeded or rolled back) from the log, so the log is empty for its
     * next saga. The entries of an unfinished execution are kept for recovery at the next start-up. truncate(id)
     * removes every entry up to and including id, so it is used only when the log holds no other execution's entries.
     */
    static void truncateIfFinished(SagaLog sagaLog, String executionId, List<SagaLogEntry> executionEntries) {
        if (executionEntries.isEmpty() || !PolyglotRecovery.isFinished(executionEntries)) {
            return;
        }
        if (sagaLog.readIncompleteSagas().allMatch(e -> executionId.equals(e.getExecutionId()))) {
            sagaLog.truncate(executionEntries.get(executionEntries.size() - 1).getId()).join();
        }
    }

    private static String responseJson(String executionId, long handoffTime, long executionTime, Throwable failure, SagaLog sagaLog, List<SagaLogEntry> sagaLogEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"executionId\":").append(JSONObject.quote(executionId));
        sb.append(",\"sagaHandoffTimeMs\":").append(handoffTime);
        sb.append(",\"sagaExecutionTimeMs\":").append(executionTime);
        if (failure != null) {
            sb.append(",\"rolledBack\":").append(failure instanceof AbortSagaException);
            sb.append(",\"error\":").append(JSONObject.quote(String.valueOf(failure)));
        }
        sb.append(",\"saga\":").append(SagaSerializer.toJson(PolyglotSaga.SAGA));
        sb.append(",\"log\":[");
        for (int i = 0; i < sagaLogEntries.size(); i++) {
            SagaLogEntry sagaLogEntry = sagaLogEntries.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{");
            sb.append("\"id\":").append(JSONObject.quote(sagaLog.toString(sagaLogEntry.getId())));
            sb.append(",\"executionId\":").append(JSONObject.quote(sagaLogEntry.getExecutionId()));
            sb.append(",\"type\":").append(JSONObject.quote(sagaLogEntry.getEntryType().toString()));
            sb.append(",\"nodeId\":").append(JSONObject.quote(sagaLogEntry.getNodeId()));
            if (sagaLogEntry.getSagaName() != null) {
                sb.append(",\"sagaName\":").append(JSONObject.quote(sagaLogEntry.getSagaName()));
            }
            if (sagaLogEntry.getJsonData() != null) {
                sb.append(",\"jsonData\":").append(sagaLogEntry.getJsonData());
            }
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private static void send(HttpServerExchange exchange, int statusCode, String contentType, String body) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        ByteBuffer responseData = ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
        exchange.setResponseContentLength(responseData.limit());
        exchange.getResponseSender().send(responseData);
    }
}
