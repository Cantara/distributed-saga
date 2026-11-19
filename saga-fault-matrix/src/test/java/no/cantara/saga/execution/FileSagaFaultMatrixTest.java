package no.cantara.saga.execution;

import no.cantara.sagalog.SagaLogPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The fault matrix on the file saga log.
 */
public class FileSagaFaultMatrixTest extends SagaFaultMatrixTest {

    Path folder;

    @Override
    protected SagaLogPool createLogPool() throws Exception {
        folder = Files.createTempDirectory("saga-fault-matrix");
        return initialize("no.cantara.sagalog.file.FileSagaLogInitializer", Map.of("filesagalog.folder", folder.toString()));
    }

    @Override
    protected void closeProvider() throws Exception {
        if (folder != null) {
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
