package no.cantara.saga.execution;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import org.json.JSONObject;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class SagaAdapterGeneric extends Adapter<Object> {

    public static final String NAME = "Generic";

    public SagaAdapterGeneric() {
        super(Object.class, NAME);
    }

    @Override
    public Object executeAction(SagaNode sagaNode, Object startInput, Map<SagaNode, Object> dependeesOutput) {
        JSONObject result = new JSONObject();
        result.put("action", "Generic Action Execution");
        result.put("unique-id", UUID.randomUUID().toString());
        result.put("response-code", 200);
        result.put("running-time-ms", new Random().nextInt(500));
        try {
            Thread.sleep(3);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return result.toString();
    }
}
