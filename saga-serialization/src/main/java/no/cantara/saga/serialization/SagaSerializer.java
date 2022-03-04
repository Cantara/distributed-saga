package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;

public class SagaSerializer {

    public static String toJson(Saga saga) {
        StringWriter w = new StringWriter();
        writeJson(saga, w);
        return w.toString();
    }

    public static void writeJson(Saga saga, Writer w) {
        try {
            w.write("{");
            w.write("\"saga\":");
            JSONObject.quote(saga.name, w);
            w.write(",\"nodes\":");
            w.write("[");
            saga.depthFirstPreOrderFullTraversal((ancestors, sagaNode) -> {
                if (ancestors.size() > 0) {
                    try {
                        w.write(",");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                writeJson(sagaNode, w);
            });
            w.write("]");
            w.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeJson(SagaNode sagaNode, Writer w) {
        try {
            w.write("{\"id\":");
            JSONObject.quote(sagaNode.id, w);
            w.write(",\"adapter\":");
            JSONObject.quote(sagaNode.adapter, w);
            w.write(",\"outgoing\":[");
            Iterator<SagaNode> it = sagaNode.outgoing.iterator();
            if (it.hasNext()) {
                JSONObject.quote(it.next().id, w);
            }
            while (it.hasNext()) {
                w.write(",");
                JSONObject.quote(it.next().id, w);
            }
            w.write("]}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
