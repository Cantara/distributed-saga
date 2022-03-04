package no.cantara.saga.samples.polyglot.adapter;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

public class WriteToObjectStore extends Adapter<JSONObject> {

    public static final String NAME = "ObjectStore";

    public WriteToObjectStore(Class<JSONObject> outputClazz) {
        super(outputClazz, NAME);
    }

    @Override
    public JSONObject executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JSONObject root = new JSONObject();
        root.put("input", sagaInput);
        JSONObject output = new JSONObject();
        output.put("action", "Upload data as object to bucket X");
        JSONObject http = new JSONObject();
        http.put("method", "PUT");
        http.put("Content-Type", "text/plain; charset=utf-8");
        http.put("url", "http://some.objectstore.com/bucket/X/path/to/object/with/id/" + UUID.randomUUID());
        http.put("response-code", "201");
        output.put("http", http);
        output.put("txid", UUID.randomUUID());
        root.put("output", output);
        return root;
    }
}
