package no.cantara.saga.samples.polyglot.adapter;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

public class PublishToPubSub extends Adapter<JSONObject> {

    public static final String NAME = "PubSub";

    public PublishToPubSub(Class<JSONObject> outputClazz) {
        super(outputClazz, NAME);
    }

    @Override
    public JSONObject executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JSONObject root = new JSONObject();
        root.put("input", sagaInput);
        JSONObject output = new JSONObject();
        output.put("action", "Publish that data has been created to topic");
        output.put("topic", "/mother/of/all/topics");
        JSONObject event = new JSONObject();
        event.put("id", String.valueOf(UUID.randomUUID()));
        event.put("category", "polyglot sample");
        output.put("event", event);
        output.put("txid", UUID.randomUUID());
        root.put("output", output);
        return root;
    }
}
