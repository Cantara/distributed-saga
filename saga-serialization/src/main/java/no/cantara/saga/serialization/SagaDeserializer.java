package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds a {@link Saga} from the JSON that {@link SagaSerializer} writes. The saga is built through the
 * {@link Saga} builder, so every rule of saga-api is checked again. Stateless and thread-safe.
 */
public class SagaDeserializer {

    private SagaDeserializer() {
    }

    /**
     * Build a Saga by de-serializing the given json representation of a saga.
     *
     * @param json the json representation of a saga.
     * @return the saga represented as a Saga object.
     * @throws SagaException if the json is not a valid saga: malformed JSON, a missing or mistyped field (the
     *                       parser's exception is the cause), or a graph that breaks a rule of the saga builder.
     */
    public static Saga fromJson(String json) throws SagaException {
        try {
            return build(json);
        } catch (JSONException e) {
            throw new SagaException("De-serialization error: " + e.getMessage(), e);
        }
    }

    private static Saga build(String json) {
        JSONObject sagaJsonObject = new JSONObject(json);
        String sagaName = sagaJsonObject.getString("saga");
        JSONArray nodes = sagaJsonObject.getJSONArray("nodes");

        /*
         * Find and build start-node
         */
        Saga.SagaBuilder sagaBuilder = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (Saga.ID_START.equals(node.getString("id"))) {
                Saga.SagaBuilder.OutgoingBuilder outgoingBuilder = Saga.start(sagaName);
                JSONArray outgoingJsonArray = node.getJSONArray("outgoing");
                String[] outgoing = new String[outgoingJsonArray.length()];
                for (int j = 0; j < outgoing.length; j++) {
                    outgoing[j] = outgoingJsonArray.getString(j);
                }
                sagaBuilder = outgoingBuilder.linkTo(outgoing);
                break;
            }
        }
        if (sagaBuilder == null) {
            throw new SagaException("De-serialization error: Missing start node");
        }

        /*
         * Build all nodes except start and end nodes.
         */
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String id = node.getString("id");
            if (Saga.ID_START.equals(id)) {
                continue;
            }
            if (Saga.ID_END.equals(id)) {
                continue;
            }
            String adapter = node.getString("adapter");
            JSONArray outgoingJsonArray = node.getJSONArray("outgoing");
            String[] outgoing = new String[outgoingJsonArray.length()];
            for (int j = 0; j < outgoing.length; j++) {
                outgoing[j] = outgoingJsonArray.getString(j);
            }
            sagaBuilder.id(id).adapter(adapter).linkTo(outgoing);
        }

        /*
         * Build saga.
         */
        return sagaBuilder.end();
    }
}
