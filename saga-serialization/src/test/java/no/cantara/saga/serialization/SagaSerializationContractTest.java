package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaException;
import no.cantara.saga.api.SagaNode;
import org.json.JSONObject;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Bad input, writer failures, JSON escaping and round trips (design review ss-1, ss-2, ss-4; ss-4 is also the
 * evidence for saga-api sa-2).
 */
public class SagaSerializationContractTest {

    @DataProvider
    public Object[][] invalidJson() {
        return new Object[][]{
                {"malformed", "{\"saga\": \"s\", \"nodes\": ["},
                {"not an object", "[1, 2]"},
                {"missing saga name", "{\"nodes\": []}"},
                {"missing nodes", "{\"saga\": \"s\"}"},
                {"nodes not an array", "{\"saga\": \"s\", \"nodes\": {}}"},
                {"node without id", "{\"saga\": \"s\", \"nodes\": [{\"adapter\": \"SagaStart\", \"outgoing\": [\"E\"]}]}"},
                {"start without outgoing", "{\"saga\": \"s\", \"nodes\": [{\"id\": \"S\", \"adapter\": \"SagaStart\"}]}"},
                {"node without adapter", "{\"saga\": \"s\", \"nodes\": ["
                        + "{\"id\": \"S\", \"adapter\": \"SagaStart\", \"outgoing\": [\"a\"]},"
                        + "{\"id\": \"a\", \"outgoing\": [\"E\"]},"
                        + "{\"id\": \"E\", \"adapter\": \"SagaEnd\", \"outgoing\": []}]}"},
        };
    }

    @Test(dataProvider = "invalidJson")
    public void thatInvalidJsonFailsWithSagaException(String what, String json) {
        SagaException e = expectThrows(SagaException.class, () -> SagaDeserializer.fromJson(json));
        assertTrue(e.getMessage().startsWith("De-serialization error"), what + ": " + e.getMessage());
        assertNotNull(e.getCause(), what + ": the parser's exception is kept as the cause");
    }

    @Test
    public void thatAGraphErrorStillFailsWithSagaException() {
        String json = "{\"saga\": \"s\", \"nodes\": ["
                + "{\"id\": \"S\", \"adapter\": \"SagaStart\", \"outgoing\": [\"missing\"]},"
                + "{\"id\": \"E\", \"adapter\": \"SagaEnd\", \"outgoing\": []}]}";
        expectThrows(SagaException.class, () -> SagaDeserializer.fromJson(json));
    }

    @Test
    public void thatAWriterFailureIsAnUncheckedIOExceptionNamingTheSaga() {
        Saga saga = Saga.start("failing writer").linkTo("a")
                .id("a").adapter("A").linkToEnd()
                .end();
        FailingWriter counting = new FailingWriter(Integer.MAX_VALUE);
        SagaSerializer.writeJson(saga, counting);
        int writes = Integer.MAX_VALUE - counting.writesLeft;
        assertTrue(writes > 10, "writes: " + writes);
        for (int failAt = 0; failAt < writes; failAt++) {
            int at = failAt;
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> SagaSerializer.writeJson(saga, new FailingWriter(at)));
            assertTrue(e.getCause() instanceof IOException);
            assertTrue(e.getMessage().contains("failing writer") || e.getMessage().contains("node"), e.getMessage());
        }
    }

    @Test
    public void thatANodeWriterFailureIsAnUncheckedIOException() {
        SagaNode node = Saga.start("s").linkToEnd().end().getStartNode();
        UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> SagaSerializer.writeJson(node, new FailingWriter(0)));
        assertTrue(e.getMessage().contains("S"), e.getMessage());
    }

    @Test
    public void thatNamesAndIdsThatNeedEscapingSurviveARoundTrip() {
        String name = "a {saga} with \"quotes\", \\backslashes\\, a\nnewline, a\ttab, \u0001 and unicode æøå ☺ 😀";
        String id = "{\"id\\}";
        String adapter = "\"adapter{\\";
        Saga saga = Saga.start(name).linkTo(id)
                .id(id).adapter(adapter).linkToEnd()
                .end();

        String json = SagaSerializer.toJson(saga);
        JSONObject parsed = new JSONObject(json); // valid JSON
        assertEquals(parsed.getString("saga"), name);
        assertEquals(parsed.getJSONArray("nodes").getJSONObject(1).getString("id"), id);
        assertEquals(parsed.getJSONArray("nodes").getJSONObject(1).getString("adapter"), adapter);

        Saga back = SagaDeserializer.fromJson(json);
        assertEquals(back.name, name);
        assertEquals(describe(back), describe(saga));
        assertEquals(SagaSerializer.toJson(back), json);
    }

    @DataProvider
    public Object[][] shapes() {
        // the shapes of the fault matrix, and the complex shape of the other tests
        Map<String, Saga> shapes = new LinkedHashMap<>();
        shapes.put("lds-write", Saga.start("lds-write").linkTo("txlog")
                .id("txlog").adapter("txlog").linkTo("persistence", "search")
                .id("persistence").adapter("persistence").linkToEnd()
                .id("search").adapter("search").linkToEnd()
                .end());
        shapes.put("lds-linear", Saga.start("lds-linear").linkTo("txlog")
                .id("txlog").adapter("txlog").linkTo("persistence")
                .id("persistence").adapter("persistence").linkToEnd()
                .end());
        shapes.put("diamond", Saga.start("diamond").linkTo("a")
                .id("a").adapter("a").linkTo("b", "c")
                .id("b").adapter("b").linkTo("d")
                .id("c").adapter("c").linkTo("d")
                .id("d").adapter("d").linkToEnd()
                .end());
        shapes.put("fan-out", Saga.start("fan-out").linkTo("a", "b", "c", "d")
                .id("a").adapter("a").linkToEnd()
                .id("b").adapter("b").linkToEnd()
                .id("c").adapter("c").linkToEnd()
                .id("d").adapter("d").linkToEnd()
                .end());
        shapes.put("empty", Saga.start("empty").linkToEnd().end());
        shapes.put("complex", Saga.start("complex").linkTo("c1", "c2", "c3")
                .id("c1").adapter("something").linkToEnd()
                .id("c2").adapter("anything").linkTo("c7")
                .id("c3").adapter("that").linkTo("c4")
                .id("c4").adapter("complex-branch").linkTo("c5", "c6")
                .id("c5").adapter("sub_route_A").linkTo("c7")
                .id("c6").adapter("sub_route_B").linkTo("c7")
                .id("c7").adapter("Aggregate_A,_B_and_anything").linkToEnd()
                .end());
        Object[][] data = new Object[shapes.size()][];
        int i = 0;
        for (Map.Entry<String, Saga> e : shapes.entrySet()) {
            data[i++] = new Object[]{e.getKey(), e.getValue()};
        }
        return data;
    }

    @Test(dataProvider = "shapes")
    public void thatEveryShapeSurvivesARoundTrip(String shape, Saga saga) {
        String json = SagaSerializer.toJson(saga);
        Saga back = SagaDeserializer.fromJson(json);
        assertEquals(back.name, saga.name, shape);
        assertEquals(describe(back), describe(saga), shape);
        assertEquals(SagaSerializer.toJson(back), json, shape);
    }

    /**
     * Every node with its adapter and its incoming and outgoing links, in order.
     */
    private static List<String> describe(Saga saga) {
        List<String> description = new ArrayList<>();
        saga.depthFirstPreOrderFullTraversal((ancestors, node) -> {
            StringBuilder sb = new StringBuilder(node.id).append('/').append(node.adapter).append(" in:");
            for (SagaNode in : node.incoming) {
                sb.append(' ').append(in.id);
            }
            sb.append(" out:");
            for (SagaNode out : node.outgoing) {
                sb.append(' ').append(out.id);
            }
            description.add(sb.toString());
        });
        return description;
    }

    /**
     * Fails on the n-th write call.
     */
    private static class FailingWriter extends Writer {
        private int writesLeft;

        FailingWriter(int failAt) {
            this.writesLeft = failAt;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (writesLeft-- <= 0) {
                throw new IOException("write failed");
            }
        }

        @Override
        public void write(String str) throws IOException {
            write(str.toCharArray(), 0, str.length());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
