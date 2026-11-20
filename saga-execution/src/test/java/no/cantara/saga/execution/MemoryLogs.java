package no.cantara.saga.execution;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Memory saga logs for the tests, and their entries as "Type:node".
 */
final class MemoryLogs {

    private static final SagaLogPool POOL = createPool();

    private MemoryLogs() {
    }

    private static SagaLogPool createPool() {
        SagaLogInitializer initializer = memoryInitializer();
        return initializer.initialize(initializer.configurationOptionsAndDefaults());
    }

    /**
     * The memory log's initializer, found as a service. Its package is not exported, so the class cannot be named here
     * and is matched by name instead of {@code instanceof}.
     */
    @SuppressWarnings("java:S1872")
    static SagaLogInitializer memoryInitializer() {
        return ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(c -> "no.cantara.sagalog.memory.MemorySagaLogInitializer".equals(c.type().getName()))
                .findFirst().orElseThrow().get();
    }

    static SagaLog newLog() {
        return POOL.connect(POOL.idFor(POOL.getLocalClusterInstanceId(), "t-" + UUID.randomUUID()));
    }

    static List<String> entries(SagaLog log, String executionId) {
        return log.readEntries(executionId).map(e -> e.getEntryType() + ":" + e.getNodeId()).collect(Collectors.toList());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
