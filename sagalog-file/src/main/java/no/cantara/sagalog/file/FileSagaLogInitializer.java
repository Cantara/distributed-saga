package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogInitializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class FileSagaLogInitializer implements SagaLogInitializer {

    public FileSagaLogInitializer() {
    }

    public FileSagaLogPool initialize(Map<String, String> configuration) {
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
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new FileSagaLogPool(folder, clusterInstanceId);
    }

    public Map<String, String> configurationOptionsAndDefaults() {
        return Map.of("filesagalog.folder", "target/test-sagalog",
                "cluster.instance-id", "01");
    }
}
