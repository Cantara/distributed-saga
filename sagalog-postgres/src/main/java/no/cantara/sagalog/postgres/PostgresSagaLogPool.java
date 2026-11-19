package no.cantara.sagalog.postgres;

import no.cantara.sagalog.AbstractSagaLogPool;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class PostgresSagaLogPool extends AbstractSagaLogPool {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresSagaLogPool.class);

    final Random random = new Random();
    final PostgresDataSource dataSource;
    final String schema;
    final String namespace;
    final String clusterInstanceId;
    final Map<SagaLogId, SagaLog> connectedLocalLogBySagaLogId = new ConcurrentHashMap<>();

    PostgresSagaLogPool(PostgresDataSource dataSource, String schema, String namespace, String clusterInstanceId) {
        super(clusterInstanceId);
        new PostgresSagaLogId(schema, namespace, clusterInstanceId, "configuration-validation"); // valid if no exception is thrown
        this.dataSource = dataSource;
        this.schema = schema;
        this.namespace = namespace;
        this.clusterInstanceId = clusterInstanceId;
    }

    @Override
    public PostgresSagaLogId idFor(String clusterInstanceId, String logName) {
        return new PostgresSagaLogId(schema, namespace, clusterInstanceId, logName);
    }

    @Override
    public Set<SagaLogId> clusterWideLogIds() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            connection.beginRequest();
            try (PreparedStatement ps = connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = ? AND left(tablename, length(?)) = ?")) {
                ps.setString(1, schema);
                String tableNamePrefix = "SAGALOG_" + namespace + "_";
                ps.setString(2, tableNamePrefix);
                ps.setString(3, tableNamePrefix);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<SagaLogId> logIds = new LinkedHashSet<>();
                    while (rs.next()) {
                        String tableName = rs.getString(1);
                        PostgresSagaLogId sagaLogId = new PostgresSagaLogId(schema, tableName);
                        logIds.add(sagaLogId);
                    }
                    return logIds;
                }
            } finally {
                connection.endRequest();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException {
        // a log closed by remove(logId) is opened again, not handed out closed
        return connectedLocalLogBySagaLogId.compute(logId, (id, existing) ->
                existing != null && !((PostgresSagaLog) existing).closed.get() ? existing : new PostgresSagaLog(dataSource, id, random, 0));
    }

    /**
     * Drops the log's table and its row in the Locks table, holding the log's advisory lock while doing so.
     *
     * @return true if the table existed
     * @throws SagaLogBusyException if another process holds the log
     */
    @Override
    protected boolean deleteExternal(SagaLogId logId) {
        SagaLog sagaLog = connectedLocalLogBySagaLogId.remove(logId);
        if (sagaLog != null) {
            try {
                sagaLog.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        PostgresSagaLogId id = (PostgresSagaLogId) logId;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            Long lockKey = null;
            try (PreparedStatement ps = connection.prepareStatement(String.format(
                    "SELECT lock_key FROM \"%s\".\"Locks\" WHERE namespace = ? AND instance_id = ? AND log_id = ?", id.getSchema()))) {
                ps.setString(1, id.getNamespace());
                ps.setString(2, id.getClusterInstanceId());
                ps.setString(3, id.getLogName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        lockKey = rs.getLong(1);
                    }
                }
            }
            if (lockKey != null && !advisoryLock(connection, "pg_try_advisory_lock", lockKey)) {
                throw new SagaLogBusyException("Saga-log is in use by another process and cannot be deleted: " + logId);
            }
            try {
                boolean existed;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = ?")) {
                    ps.setString(1, id.getSchema());
                    ps.setString(2, id.getTableName());
                    try (ResultSet rs = ps.executeQuery()) {
                        existed = rs.next();
                    }
                }
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate(String.format("DROP TABLE IF EXISTS \"%s\".\"%s\"", id.getSchema(), id.getTableName()));
                }
                try (PreparedStatement ps = connection.prepareStatement(String.format(
                        "DELETE FROM \"%s\".\"Locks\" WHERE namespace = ? AND instance_id = ? AND log_id = ?", id.getSchema()))) {
                    ps.setString(1, id.getNamespace());
                    ps.setString(2, id.getClusterInstanceId());
                    ps.setString(3, id.getLogName());
                    ps.executeUpdate();
                }
                return existed;
            } finally {
                if (lockKey != null) {
                    advisoryLock(connection, "pg_advisory_unlock", lockKey);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean advisoryLock(Connection connection, String function, long key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + function + "(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    @Override
    public void shutdown() {
        LOG.info("Shutting down {}...", PostgresSagaLogPool.class.getSimpleName());
        for (Map.Entry<SagaLogId, SagaLog> entry : connectedLocalLogBySagaLogId.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        dataSource.close();
        LOG.info("Shutdown of {} complete", PostgresSagaLogPool.class.getSimpleName());
    }
}
