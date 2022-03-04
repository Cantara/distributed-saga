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
        return connectedLocalLogBySagaLogId.computeIfAbsent(logId, id -> new PostgresSagaLog(dataSource, id, random, 0));
    }

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
        return sagaLog != null;
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
