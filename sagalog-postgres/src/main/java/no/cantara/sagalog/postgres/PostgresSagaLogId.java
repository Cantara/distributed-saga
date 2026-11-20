package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogId;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The id of a PostgreSQL saga log: the table {@code "<schema>"."SAGALOG_<namespace>_<instance-id>_<log-name>"}.
 * <p>
 * Every part is checked where it enters, because the names are put into SQL as identifiers: the schema and the log
 * name are letters, digits and {@code _ -} (the log name also {@code . : $}); the namespace and the instance id are
 * letters, digits and {@code -}, since {@code _} separates the parts. PostgreSQL truncates identifiers longer than 63
 * bytes, which would put two logs in one table, so a longer schema or table name is refused.
 */
class PostgresSagaLogId implements SagaLogId, Comparable<PostgresSagaLogId> {

    /** PostgreSQL's identifier limit (NAMEDATALEN - 1), in bytes. */
    static final int MAX_IDENTIFIER_BYTES = 63;

    static final Pattern SCHEMA_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    static final Pattern NAMESPACE_OR_INSTANCE_ID = Pattern.compile("[A-Za-z0-9-]+");
    static final Pattern LOG_NAME = Pattern.compile("[A-Za-z0-9_.:$-]+");

    static final Pattern sagaLogTableNamePattern = Pattern.compile("SAGALOG[_](?<namespace>[^_]+)[_](?<instanceId>[^_]+)[_](?<logName>.+)");

    private static final Comparator<PostgresSagaLogId> ORDER = Comparator
            .comparing((PostgresSagaLogId id) -> id.schema)
            .thenComparing(id -> id.namespace)
            .thenComparing(id -> id.clusterInstanceId)
            .thenComparing(id -> id.logName);

    final String schema;
    final String namespace;
    final String clusterInstanceId;
    final String logName;

    /**
     * @throws IllegalArgumentException if a part is null or holds a character outside its set, or the schema or the
     *                                  table name is longer than 63 bytes
     */
    PostgresSagaLogId(String schema, String namespace, String clusterInstanceId, String logName) {
        this.schema = check("schema (cluster.owner)", schema, SCHEMA_PATTERN);
        this.namespace = check("namespace (cluster.name)", namespace, NAMESPACE_OR_INSTANCE_ID);
        this.clusterInstanceId = check("clusterInstanceId (cluster.instance-id)", clusterInstanceId, NAMESPACE_OR_INSTANCE_ID);
        this.logName = check("logName", logName, LOG_NAME);
        checkLength("table-name", getTableName());
    }

    /**
     * An id from a table name, as listed in {@code pg_tables}.
     *
     * @throws IllegalArgumentException if the table name is not a saga-log table name, or a part is not valid
     */
    PostgresSagaLogId(String schema, String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        Matcher m = sagaLogTableNamePattern.matcher(tableName);
        if (!m.matches()) {
            throw new IllegalArgumentException("table-name does not match sagalog pattern: " + tableName);
        }
        this.schema = check("schema (cluster.owner)", schema, SCHEMA_PATTERN);
        this.namespace = check("namespace (cluster.name)", m.group("namespace"), NAMESPACE_OR_INSTANCE_ID);
        this.clusterInstanceId = check("clusterInstanceId (cluster.instance-id)", m.group("instanceId"), NAMESPACE_OR_INSTANCE_ID);
        this.logName = check("logName", m.group("logName"), LOG_NAME);
        checkLength("table-name", getTableName());
    }

    private static String check(String what, String value, Pattern allowed) {
        if (value == null) {
            throw new IllegalArgumentException(what + " cannot be null");
        }
        if (!allowed.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must match " + allowed.pattern() + ": '" + value + "'");
        }
        return checkLength(what, value);
    }

    private static String checkLength(String what, String identifier) {
        int bytes = identifier.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException(what + " is " + bytes + " bytes, more than PostgreSQL's " + MAX_IDENTIFIER_BYTES
                    + ": '" + identifier + "'");
        }
        return identifier;
    }

    /**
     * Checks a database role name for use as a quoted identifier: not empty, no control characters, at most 63 bytes.
     */
    static String checkRoleName(String what, String role) {
        if (role == null || role.isEmpty()) {
            throw new IllegalArgumentException(what + " cannot be empty");
        }
        if (role.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " cannot hold control characters");
        }
        return checkLength(what, role);
    }

    /**
     * @return the identifier in double quotes, with any double quote in it doubled
     */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * @return {@code "<schema>"."<table>"}
     */
    String qualifiedTableName() {
        return quote(schema) + '.' + quote(getTableName());
    }

    /**
     * @return {@code "<schema>"."Locks"}
     */
    String qualifiedLocksTableName() {
        return locksTable(schema);
    }

    static String locksTable(String schema) {
        return quote(schema) + ".\"Locks\"";
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
        return ORDER.compare(this, o);
    }
}
