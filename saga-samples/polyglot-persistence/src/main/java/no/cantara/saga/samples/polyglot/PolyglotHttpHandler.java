package no.cantara.saga.samples.polyglot;

import io.undertow.io.Receiver;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaExecution;
import no.cantara.saga.execution.SagaHandoffControl;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.saga.samples.polyglot.adapter.PublishToPubSub;
import no.cantara.saga.samples.polyglot.adapter.WriteToGraph;
import no.cantara.saga.samples.polyglot.adapter.WriteToObjectStore;
import no.cantara.saga.samples.polyglot.adapter.WriteToRDBMS;
import no.cantara.saga.serialization.SagaSerializer;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class PolyglotHttpHandler implements HttpHandler {
    private final SelectableThreadPoolExectutor executorService;
    private final SagaLogPool sagaLogPool;
    private final AdapterLoader adapterLoader;

    public PolyglotHttpHandler(SelectableThreadPoolExectutor executorService, SagaLogPool sagaLogPool) {
        this.executorService = executorService;
        this.sagaLogPool = sagaLogPool;

        /*
         * Register all adapters/drivers that will be used in sagas.
         */
        adapterLoader = new AdapterLoader()
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

        exchange.setStatusCode(400);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send("Unsupported method: " + exchange.getRequestMethod());
    }

    private Receiver.FullBytesCallback PUTCallback() {
        return (exchange, buf) -> {


            /*
             * Create Saga that will first write to 3 different persistence-technologies
             * in parallel, then publish an event to a pub-sub technology.
             */
            Saga polyglotSaga = Saga.start("Polyglot Saga")
                    .linkTo("rdbms", "graph", "objectstore")
                    .id("rdbms").adapter(WriteToRDBMS.NAME).linkTo("pubsub")
                    .id("graph").adapter(WriteToGraph.NAME).linkTo("pubsub")
                    .id("objectstore").adapter(WriteToObjectStore.NAME).linkTo("pubsub")
                    .id("pubsub").adapter(PublishToPubSub.NAME).linkToEnd()
                    .end();


            /*
             * Prepare Saga input
             */
            JSONObject inputRoot = new JSONObject();
            inputRoot.put("resource-path", exchange.getRequestPath());
            inputRoot.put("data", new String(buf, StandardCharsets.UTF_8));

            /*
             * Execute Saga
             */
            String executionId = UUID.randomUUID().toString();
            SagaLogId logId = sagaLogPool.registerInstanceLocalIdFor(Thread.currentThread().getName());
            SagaLog sagaLog = sagaLogPool.tryTakeOwnership(new SagaLogOwner("local-undertow"), logId);

            try {

                SagaExecution sagaExecution = new SagaExecution(sagaLog, executorService, polyglotSaga, adapterLoader);
                long executionStartTime = System.currentTimeMillis();
                SagaHandoffControl handoffControl = sagaExecution.executeSaga(executionId, inputRoot, false, r -> {
                });
                handoffControl.getHandoffFuture().join(); // wait for saga handoff
                long handoffTime = System.currentTimeMillis() - executionStartTime;
                handoffControl.getCompletionFuture().join();
                long executionTime = System.currentTimeMillis() - executionStartTime;

                /*
                 * Respond with result
                 */
                exchange.setStatusCode(201);
                exchange.getResponseHeaders().put(new HttpString("Content-Type"), "application/json; charset=utf-8");
                StringBuilder sb = new StringBuilder();
                sb.append("{");
                sb.append("\"sagaHandoffTimeMs\":").append(handoffTime);
                sb.append(",");
                sb.append("\"sagaExecutionTimeMs\":").append(executionTime);
                sb.append(",");
                sb.append("\"saga\":").append(SagaSerializer.toJson(polyglotSaga));
                sb.append(",");
                sb.append("\"log\":[");
                List<SagaLogEntry> sagaLogEntries = sagaLog.readEntries(executionId).collect(Collectors.toList());
                for (int i = 0; i < sagaLogEntries.size(); i++) {
                    SagaLogEntry sagaLogEntry = sagaLogEntries.get(i);
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append("{");
                    sb.append("\"id\":").append(JSONObject.quote(sagaLog.toString(sagaLogEntry.getId()))).append(",");
                    sb.append("\"executionId\":").append(JSONObject.quote(sagaLogEntry.getExecutionId())).append(",");
                    sb.append("\"type\":").append(JSONObject.quote(sagaLogEntry.getEntryType().toString())).append(",");
                    sb.append("\"nodeId\":").append(JSONObject.quote(sagaLogEntry.getNodeId())).append(",");
                    if (sagaLogEntry.getSagaName() != null) {
                        sb.append("\"sagaName\":").append(JSONObject.quote(sagaLogEntry.getSagaName())).append(",");
                    }
                    if (sagaLogEntry.getJsonData() != null) {
                        sb.append("\"jsonData\":").append(sagaLogEntry.getJsonData());
                    }
                    sb.append("}");
                }
                sb.append("]");
                sb.append("}");
                ByteBuffer responseData = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
                exchange.setResponseContentLength(responseData.limit());
                exchange.getResponseSender().send(responseData);

            } finally {
                sagaLogPool.releaseOwnership(logId);
            }
        };
    }
}
