package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Iterator;

/**
 * Writes a {@link Saga} as compact JSON:
 * <pre>
 * {"saga":"name","nodes":[{"id":"S","adapter":"SagaStart","outgoing":["a"]},{"id":"a","adapter":"A","outgoing":["E"]},...]}
 * </pre>
 * The nodes are written depth-first in pre-order from the start node {@code S}, each once, with their outgoing links
 * in declaration order. Every string is escaped as a JSON string, so a saga name may hold any character.
 * {@link SagaDeserializer} reads it back. Stateless and thread-safe.
 */
public class SagaSerializer {

    private SagaSerializer() {
    }

    /**
     * @param saga the saga
     * @return the saga as compact JSON
     */
    public static String toJson(Saga saga) {
        StringWriter w = new StringWriter();
        writeJson(saga, w);
        return w.toString();
    }

    /**
     * Writes the saga as compact JSON to a writer.
     *
     * @param saga the saga
     * @param w    the writer
     * @throws UncheckedIOException if the writer fails; its cause is the writer's IOException
     */
    public static void writeJson(Saga saga, Writer w) throws UncheckedIOException {
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
                        throw writingFailed(saga, e);
                    }
                }
                writeJson(sagaNode, w);
            });
            w.write("]");
            w.write("}");
        } catch (IOException e) {
            throw writingFailed(saga, e);
        }
    }

    private static UncheckedIOException writingFailed(Saga saga, IOException e) {
        return new UncheckedIOException("Writing saga " + saga.name + " as JSON", e);
    }

    /**
     * Writes one node as a compact JSON object: its id, adapter and the ids of its outgoing links.
     *
     * @param sagaNode the node
     * @param w        the writer
     * @throws UncheckedIOException if the writer fails; its cause is the writer's IOException
     */
    public static void writeJson(SagaNode sagaNode, Writer w) throws UncheckedIOException {
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
            throw new UncheckedIOException("Writing saga node " + sagaNode.id + " as JSON", e);
        }
    }
}
