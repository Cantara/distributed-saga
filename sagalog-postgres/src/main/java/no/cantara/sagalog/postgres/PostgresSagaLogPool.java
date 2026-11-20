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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

class PostgresSagaLogPool extends AbstractSagaLogPool {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresSagaLogPool.class);

    final Random random = new Random();
    final PostgresDataSource dataSource;
    final String schema;
    final String namespace;
    final String clusterInstanceId;
    final Duration relockWait;

    PostgresSagaLogPool(PostgresDataSource dataSource, String schema, String namespace, String clusterInstanceId) {
        this(dataSource, schema, namespace, clusterInstanceId, LogSession.DEFAULT_RELOCK_WAIT);
    }

    PostgresSagaLogPool(PostgresDataSource dataSource, String schema, String namespace, String clusterInstanceId, Duration relockWait) {
        super(clusterInstanceId);
        new PostgresSagaLogId(schema, namespace, clusterInstanceId, "x"); // valid if no exception is thrown
        this.dataSource = dataSource;
        this.schema = schema;
        this.namespace = namespace;
        this.clusterInstanceId = clusterInstanceId;
        this.relockWait = relockWait;
    }

    @Override
    public PostgresSagaLogId idFor(String clusterInstanceId, String logName) {
        return new PostgresSagaLogId(schema, namespace, clusterInstanceId, logName);
    }

    /**
     * Lists the logs of this namespace in the schema. A table whose name is not a valid saga-log id is skipped with a
     * warning.
     */
    @Override
    public Set<SagaLogId> clusterWideLogIds() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement ps = connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = ? AND left(tablename, length(?)) = ?")) {
                ps.setString(1, schema);
                String tableNamePrefix = "SAGALOG_" + namespace + "_";
                ps.setString(2, tableNamePrefix);
                ps.setString(3, tableNamePrefix);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<SagaLogId> logIds = new LinkedHashSet<>();
                    while (rs.next()) {
                        String tableName = rs.getString(1);
                        try {
                            logIds.add(new PostgresSagaLogId(schema, tableName));
                        } catch (IllegalArgumentException e) {
                            LOG.warn("Skipping table \"{}\".\"{}\": not a saga log ({})", schema, tableName, e.getMessage());
                        }
                    }
                    return logIds;
                }
            }
        } catch (SQLException e) {
            throw new PostgresSagaLogException("clusterWideLogIds", "schema " + schema, e);
        }
    }

    @Override
    protected SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException {
        return new PostgresSagaLog(dataSource, logId, random, relockWait);
    }

    /**
     * Drops the log's table and its row in the Locks table, holding the log's advisory lock while doing so.
     *
     * @return true if the table existed
     * @throws SagaLogBusyException     if another process holds the log
     * @throws PostgresSagaLogException if the database fails
     */
    // java:S2077: only identifiers are formatted into this SQL, from PostgresSagaLogId: checked against a strict
    // pattern and quoted (sp-6). Values are bound parameters.
    @Override
    @SuppressWarnings("java:S2077")
    protected boolean deleteExternal(SagaLogId logId) {
        PostgresSagaLogId id = (PostgresSagaLogId) logId;
        String resource = "saga log " + id.getSchema() + "." + id.getTableName();
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new PostgresSagaLogException("delete", resource, e);
        }
        RuntimeException failure = null;
        try {
            connection.setAutoCommit(false);
            Long lockKey = LockRegistry.findKey(connection, id);
            if (lockKey != null && !LockRegistry.tryLock(connection, lockKey)) {
                throw new SagaLogBusyException("Saga-log is in use by another process and cannot be deleted: " + logId);
            }
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
                st.executeUpdate("DROP TABLE IF EXISTS " + id.qualifiedTableName());
            }
            LockRegistry.delete(connection, id);
            connection.commit();
            return existed;
        } catch (SQLException e) {
            failure = new PostgresSagaLogException("delete", resource, e);
            throw failure;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            release(connection, resource, failure);
        }
    }

    /**
     * Gives the connection back with no advisory lock and no transaction on it, or evicts it if that fails.
     */
    private void release(Connection connection, String resource, RuntimeException failure) {
        try {
            connection.rollback();
            LockRegistry.unlockAll(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                LOG.warn("Unable to release the lock of {} cleanly; evicting its connection", resource, e);
            }
            dataSource.evictConnection(connection);
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("Unable to give back the connection of {}", resource, e);
        }
    }

    /**
     * Closes every open log (as the base pool does, which also forgets the ownerships), then the connection pool,
     * also when closing a log fails.
     */
    @Override
    public void shutdown() {
        LOG.info("Shutting down {}...", PostgresSagaLogPool.class.getSimpleName());
        RuntimeException failure = null;
        try {
            super.shutdown();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            dataSource.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
        LOG.info("Shutdown of {} complete", PostgresSagaLogPool.class.getSimpleName());
    }
}
