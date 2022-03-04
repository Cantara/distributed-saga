package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogId;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PostgresSagaLogId implements SagaLogId, Comparable<PostgresSagaLogId> {

    static final Pattern sagaLogTableNamePattern = Pattern.compile("SAGALOG[_](?<namespace>[^_]+)[_](?<instanceId>[^_]+)[_](?<logName>.+)");

    final String schema;
    final String namespace;
    final String clusterInstanceId;
    final String logName;

    PostgresSagaLogId(String schema, String namespace, String clusterInstanceId, String logName) {
        if (schema == null) {
            throw new IllegalArgumentException("schema cannot be null");
        }
        if (schema.contains(".")) {
            throw new IllegalArgumentException("clusterOwner cannot contain '.'");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("namespace cannot be null");
        }
        if (namespace.contains("_")) {
            throw new IllegalArgumentException("namespace cannot contain '_'");
        }
        if (clusterInstanceId == null) {
            throw new IllegalArgumentException("clusterInstanceId cannot be null");
        }
        if (clusterInstanceId.contains("_")) {
            throw new IllegalArgumentException("clusterInstanceId cannot contain '_'");
        }
        if (logName == null) {
            throw new IllegalArgumentException("logName cannot be null");
        }
        this.schema = schema;
        this.namespace = namespace;
        this.clusterInstanceId = clusterInstanceId;
        this.logName = logName;
        String qTableName = getTableName();
        Matcher m = sagaLogTableNamePattern.matcher(qTableName);
        if (!m.matches()) {
            throw new IllegalArgumentException("table-name does not match sagalog pattern: " + qTableName);
        }
    }

    PostgresSagaLogId(String schema, String tableName) {
        this.schema = schema;
        Matcher m = sagaLogTableNamePattern.matcher(tableName);
        if (!m.matches()) {
            throw new RuntimeException("table-name does not match sagalog pattern: " + tableName);
        }
        this.namespace = m.group("namespace");
        this.clusterInstanceId = m.group("instanceId");
        this.logName = m.group("logName");
    }

    String getTableName() {
        return "SAGALOG_" + namespace + "_" + clusterInstanceId + "_" + logName;
    }

    String getSchema() {
        return schema;
    }

    String getNamespace() {
        return namespace;
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
        PostgresSagaLogId that = (PostgresSagaLogId) o;
        return schema.equals(that.schema) &&
                namespace.equals(that.namespace) &&
                clusterInstanceId.equals(that.clusterInstanceId) &&
                logName.equals(that.logName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, namespace, clusterInstanceId, logName);
    }

    @Override
    public String toString() {
        return "PostgresSagaLogId{" +
                "schema='" + getSchema() + '\'' +
                ", table='" + getTableName() + '\'' +
                '}';
    }

    @Override
    public int compareTo(PostgresSagaLogId o) {
        int ownerDiff = schema.compareTo(o.schema);
        if (ownerDiff != 0) {
            return 1000000 * ownerDiff;
        }
        int namespaceDiff = namespace.compareTo(o.namespace);
        if (namespaceDiff != 0) {
            return 10000 * namespaceDiff;
        }
        int instanceIdDiff = clusterInstanceId.compareTo(o.clusterInstanceId);
        if (instanceIdDiff != 0) {
            return 100 * instanceIdDiff;
        }
        return logName.compareTo(o.logName);
    }
}
