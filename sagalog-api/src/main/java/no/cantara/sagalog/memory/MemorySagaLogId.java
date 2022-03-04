package no.cantara.sagalog.memory;

import no.cantara.sagalog.SagaLogId;

import java.util.Objects;

class MemorySagaLogId implements SagaLogId {
    private final String clusterInstanceId;
    private final String logName;

    MemorySagaLogId(String clusterInstanceId, String logName) {
        this.clusterInstanceId = clusterInstanceId;
        if (logName == null) {
            throw new IllegalArgumentException("logName cannot be null");
        }
        this.logName = logName;
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
        MemorySagaLogId that = (MemorySagaLogId) o;
        return clusterInstanceId.equals(that.clusterInstanceId) &&
                logName.equals(that.logName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterInstanceId, logName);
    }

    @Override
    public String toString() {
        return "MemorySagaLogId{" +
                "clusterInstanceId='" + clusterInstanceId + '\'' +
                ", logName='" + logName + '\'' +
                '}';
    }
}
