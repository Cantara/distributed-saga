package no.cantara.sagalog;

import java.util.Objects;

public class SagaLogEntry {

    final SagaLogEntryId id;
    final String executionId;
    final SagaLogEntryType entryType;
    final String nodeId;
    final String sagaName;
    final String jsonData;

    SagaLogEntry(SagaLogEntryId id, String executionId, SagaLogEntryType entryType, String nodeId, String sagaName, String jsonData) {
        if (id == null) {
            throw new NullPointerException("id");
        }
        if (executionId == null) {
            throw new NullPointerException("executionId");
        }
        if (entryType == null) {
            throw new NullPointerException("entryType");
        }
        if (nodeId == null) {
            throw new NullPointerException("nodeId");
        }
        this.id = id;
        this.executionId = executionId;
        this.entryType = entryType;
        this.nodeId = nodeId;
        this.sagaName = sagaName;
        this.jsonData = jsonData;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaLogEntry that = (SagaLogEntry) o;
        return id.equals(that.id) &&
                executionId.equals(that.executionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, executionId);
    }

    @Override
    public String toString() {
        return "SagaLogEntry{" +
                "id=" + id +
                ", executionId='" + executionId + '\'' +
                ", entryType=" + entryType +
                ", nodeId='" + nodeId + '\'' +
                ", sagaName='" + sagaName + '\'' +
                ", jsonData='" + jsonData + '\'' +
                '}';
    }

    public SagaLogEntryId getId() {
        return id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public SagaLogEntryType getEntryType() {
        return entryType;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getSagaName() {
        return sagaName;
    }

    public String getJsonData() {
        return jsonData;
    }
}
