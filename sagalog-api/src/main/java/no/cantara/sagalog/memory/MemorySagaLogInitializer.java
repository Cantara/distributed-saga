package no.cantara.sagalog.memory;

import no.cantara.sagalog.SagaLogInitializer;

import java.util.Map;

public class MemorySagaLogInitializer implements SagaLogInitializer {

    public MemorySagaLogInitializer() {
    }

    public MemorySagaLogPool initialize(Map<String, String> configuration) {
        return new MemorySagaLogPool(configuration.getOrDefault("cluster.instance-id", "TheOnlyInstance"));
    }

    public Map<String, String> configurationOptionsAndDefaults() {
        return Map.of("cluster.instance-id", "TheOnlyInstance");
    }
}
