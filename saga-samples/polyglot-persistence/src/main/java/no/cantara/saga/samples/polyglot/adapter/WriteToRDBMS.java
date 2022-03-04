package no.cantara.saga.samples.polyglot.adapter;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

public class WriteToRDBMS extends Adapter<JSONObject> {

    public static final String NAME = "RDBMS";

    public WriteToRDBMS(Class<JSONObject> outputClazz) {
        super(outputClazz, NAME);
    }

    @Override
    public JSONObject executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JSONObject root = new JSONObject();
        root.put("input", sagaInput);
        JSONObject output = new JSONObject();
        output.put("action", "Add data record to table X");
        output.put("SQL", "INSERT INTO X(column1, column2) VALUES('foo', '" + UUID.randomUUID() + "');");
        output.put("txid", UUID.randomUUID());
        root.put("output", output);
        return root;
    }
}
