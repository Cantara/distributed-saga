package no.cantara.saga.execution;

import no.cantara.sagalog.SagaLogPool;

import java.util.Map;

/**
 * The fault matrix on the memory saga log.
 */
public class MemorySagaFaultMatrixTest extends SagaFaultMatrixTest {

    @Override
    protected SagaLogPool createLogPool() {
        return initialize("no.cantara.sagalog.memory.MemorySagaLogInitializer", Map.of());
    }
}
