package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogId;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

class FileSagaLogId implements SagaLogId {
    private final Path parentFolder;
    private final String clusterInstanceId;
    private final String logName;

    FileSagaLogId(Path parentFolder, String clusterInstanceId, String logName) {
        if (parentFolder == null) {
            throw new IllegalArgumentException("parentFolder cannot be null");
        }
        if (clusterInstanceId == null) {
            throw new IllegalArgumentException("clusterInstanceId cannot be null");
        }
        if (logName == null) {
            throw new IllegalArgumentException("logName cannot be null");
        }
        this.parentFolder = parentFolder.toAbsolutePath().normalize();
        this.clusterInstanceId = clusterInstanceId;
        this.logName = logName;
    }

    FileSagaLogId(Path _path) {
        Path path = _path.toAbsolutePath().normalize();
        String filename = path.getFileName().toString();
        if (!filename.endsWith(".sagalog")) {
            throw new IllegalArgumentException(String.format("Malformed saga-log-id filename does not end with .sagalog: %s", filename));
        }
        int indexOfClusterAndInternalIdSeparator = filename.indexOf(".--.");
        if (indexOfClusterAndInternalIdSeparator == -1) {
            throw new IllegalArgumentException(String.format("Malformed saga-log-id path missing separator \".--.\": %s", filename));
        }
        this.parentFolder = path.getParent();
        this.clusterInstanceId = filename.substring(0, indexOfClusterAndInternalIdSeparator);
        this.logName = filename.substring(indexOfClusterAndInternalIdSeparator + ".--.".length(), filename.length() - ".sagalog".length());
        if (!filename.equals(getFilename())) {
            throw new IllegalStateException(String.format("Internal bug. Filename cannot not correctly recreated from parts after parsing existing filename: %s", filename));
        }
    }

    String getFilename() {
        return clusterInstanceId + ".--." + logName + ".sagalog";
    }

    Path getPath() {
        return Paths.get(parentFolder.toString(), getFilename()).toAbsolutePath();
    }

    @Override
    public String getClusterInstanceId() {
        return clusterInstanceId;
    }

    @Override
    public String getLogName() {
        return logName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileSagaLogId that = (FileSagaLogId) o;
        return parentFolder.equals(that.parentFolder) &&
                clusterInstanceId.equals(that.clusterInstanceId) &&
                logName.equals(that.logName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parentFolder, clusterInstanceId, logName);
    }

    @Override
    public String toString() {
        return "FileSagaLogId{path=" + getPath() + "'}";
    }
}
