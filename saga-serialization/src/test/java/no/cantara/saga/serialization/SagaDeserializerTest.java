package no.cantara.saga.serialization;

import no.cantara.saga.api.Saga;
import org.skyscreamer.jsonassert.JSONAssert;
import org.testng.annotations.Test;

public class SagaDeserializerTest {

    @Test
    public void thatSagaFromJsonDeserializationDoesNotCorruptData() {
        String expected = getSagaJsonSample();
        Saga saga = SagaDeserializer.fromJson(expected);
        String actual = SagaSerializer.toJson(saga);
        JSONAssert.assertEquals(expected, actual, false);
    }

    private String getSagaJsonSample() {
        return "{\n" +
                "  \"saga\": \"Complex saga\",\n" +
                "  \"nodes\": [\n" +
                "    {\n" +
                "      \"outgoing\": [\n" +
                "        \"c1\",\n" +
                "        \"c2\",\n" +
                "        \"c3\"\n" +
                "      ],\n" +
                "      \"adapter\": \"SagaStart\",\n" +
                "      \"id\": \"S\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"E\"],\n" +
                "      \"adapter\": \"Adapter_C1\",\n" +
                "      \"id\": \"c1\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [],\n" +
                "      \"adapter\": \"SagaEnd\",\n" +
                "      \"id\": \"E\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"c7\"],\n" +
                "      \"adapter\": \"Adapter_C2\",\n" +
                "      \"id\": \"c2\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"E\"],\n" +
                "      \"adapter\": \"Adapter_C7\",\n" +
                "      \"id\": \"c7\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"c4\"],\n" +
                "      \"adapter\": \"Adapter_C3\",\n" +
                "      \"id\": \"c3\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\n" +
                "        \"c5\",\n" +
                "        \"c6\"\n" +
                "      ],\n" +
                "      \"adapter\": \"Adapter_C4\",\n" +
                "      \"id\": \"c4\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"c7\"],\n" +
                "      \"adapter\": \"Adapter_C5\",\n" +
                "      \"id\": \"c5\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"outgoing\": [\"c7\"],\n" +
                "      \"adapter\": \"Adapter_C6\",\n" +
                "      \"id\": \"c6\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }
}
