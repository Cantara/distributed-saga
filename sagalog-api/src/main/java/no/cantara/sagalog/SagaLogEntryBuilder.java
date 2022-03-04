package no.cantara.sagalog;

public class SagaLogEntryBuilder {

    SagaLogEntryId id;
    String executionId;
    SagaLogEntryType entryType;
    String nodeId;
    String sagaName;
    String jsonData;

    public SagaLogEntryBuilder() {
    }

    public SagaLogEntry build() {
        return new SagaLogEntry(id, executionId, entryType, nodeId, sagaName, jsonData);
    }

    public SagaLogEntryBuilder startSaga(String executionId, String sagaName, String sagaInputJson) {
        return executionId(executionId).entryType(SagaLogEntryType.Start).nodeId("S").sagaName(sagaName).jsonData(sagaInputJson);
    }

    public SagaLogEntryBuilder startAction(String executionId, String nodeId) {
        return executionId(executionId).entryType(SagaLogEntryType.Start).nodeId(nodeId);
    }

    public SagaLogEntryBuilder endAction(String executionId, String nodeId, String actionOutputJson) {
        return executionId(executionId).entryType(SagaLogEntryType.End).nodeId(nodeId).jsonData(actionOutputJson);
    }

    public SagaLogEntryBuilder endSaga(String executionId) {
        return executionId(executionId).entryType(SagaLogEntryType.End).nodeId("E");
    }

    public SagaLogEntryBuilder abort(String executionId, String nodeId) {
        return executionId(executionId).entryType(SagaLogEntryType.Abort).nodeId(nodeId);
    }

    public SagaLogEntryBuilder compDone(String executionId, String nodeId) {
        return executionId(executionId).entryType(SagaLogEntryType.Comp).nodeId(nodeId);
    }

    public SagaLogEntryBuilder control() {
        return executionId("c").entryType(SagaLogEntryType.Ignore).nodeId("c");
    }

    public SagaLogEntryBuilder id(SagaLogEntryId id) {
        this.id = id;
        return this;
    }

    public SagaLogEntryBuilder executionId(String executionId) {
        this.executionId = executionId;
        return this;
    }

    public SagaLogEntryBuilder entryType(SagaLogEntryType entryType) {
        this.entryType = entryType;
        return this;
    }

    public SagaLogEntryBuilder nodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public SagaLogEntryBuilder sagaName(String sagaName) {
        this.sagaName = sagaName;
        return this;
    }

    public SagaLogEntryBuilder jsonData(String jsonData) {
        this.jsonData = jsonData;
        return this;
    }

    public SagaLogEntryId id() {
        return id;
    }

    public String executionId() {
        return executionId;
    }

    public SagaLogEntryType entryType() {
        return entryType;
    }

    public String nodeId() {
        return nodeId;
    }

    public String sagaName() {
        return sagaName;
    }

    public String jsonData() {
        return jsonData;
    }

    @Override
    public String toString() {
        return "SagaLogEntryBuilder{" +
                "id=" + id +
                ", executionId='" + executionId + '\'' +
                ", entryType=" + entryType +
                ", nodeId='" + nodeId + '\'' +
                ", sagaName='" + sagaName + '\'' +
                ", jsonData='" + jsonData + '\'' +
                '}';
    }
}
