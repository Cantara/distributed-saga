package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class FileSagaLogInitializer implements SagaLogInitializer {

    public FileSagaLogInitializer() {
    }

    /**
     * @throws IllegalArgumentException if a required parameter is missing
     * @throws UncheckedIOException     if the folder cannot be created
     */
    @Override
    public SagaLogPool initialize(Map<String, String> configuration) {
        String filesagalogFolder = configuration.get("filesagalog.folder");
        if (filesagalogFolder == null || filesagalogFolder.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration parameter: filesagalog.folder");
        }
        Path folder = Paths.get(filesagalogFolder);
        String clusterInstanceId = configuration.get("cluster.instance-id");
        if (clusterInstanceId == null || clusterInstanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration parameter: cluster.instance-id");
        }
        try {
            createDirectories(folder.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the saga-log folder " + folder, e);
        }
        return new FileSagaLogPool(folder, clusterInstanceId);
    }

    /**
     * Creates the folder and its missing parents, and syncs the folder above each one created so that it is durable.
     */
    private static void createDirectories(Path folder) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path p = folder; p != null && !Files.isDirectory(p); p = p.getParent()) {
            missing.push(p);
        }
        Files.createDirectories(folder);
        for (Path created : missing) {
            Directories.sync(created.getParent());
        }
    }

    @Override
    public Map<String, String> configurationOptionsAndDefaults() {
        return Map.of("filesagalog.folder", "target/test-sagalog",
                "cluster.instance-id", "01");
    }
}
