package no.cantara.saga.samples.polyglot.adapter;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

public class WriteToGraph extends Adapter<JSONObject> {

    public static final String NAME = "Graph";

    public WriteToGraph(Class<JSONObject> outputClazz) {
        super(outputClazz, NAME);
    }

    @Override
    public JSONObject executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JSONObject root = new JSONObject();
        root.put("input", sagaInput);
        JSONObject output = new JSONObject();
        output.put("action", "Create NODES and LINKS");
        output.put("GraphOp", "CREATE Node('" + UUID.randomUUID() + "'); CREATE Link from recent node to Node('" + UUID.randomUUID() + "');");
        output.put("txid", UUID.randomUUID());
        root.put("output", output);
        return root;
    }
}
