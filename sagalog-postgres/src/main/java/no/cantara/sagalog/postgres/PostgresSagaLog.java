package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

class PostgresSagaLog implements SagaLog, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresSagaLog.class);

    final AtomicReference<UUID> prevId = new AtomicReference<>(UuidV7.generate());

    final Random random;
    final PostgresDataSource dataSource;
    final PostgresSagaLogId sagaLogId;
    final AtomicBoolean closed = new AtomicBoolean(false);

    final int failOnXAttempts;
    final Object sync = new Object();
    Connection theConnection;
    boolean lockIsHeld;
    long lockKey;
    boolean reconnecting = false;

    PostgresSagaLog(PostgresDataSource dataSource, SagaLogId _sagaLogId, Random random, int failOnXAttempts) {
        this.dataSource = dataSource;
        this.sagaLogId = (PostgresSagaLogId) _sagaLogId;
        this.random = random;
        this.failOnXAttempts = failOnXAttempts;
        boolean opened = false;
        try {
            this.lockKey = getOrAssignLockKey(sagaLogId);
            boolean success = runTransaction((Function<Connection, Boolean>) connection -> tryLock(connection, lockKey));
            if (!success) {
                throw new SagaLogBusyException(String.format("Unable to acquire exclusive lock on %s with lock-key %d.", sagaLogId, lockKey));
            }
            initSagalogTable();
            opened = true;
        } finally {
            if (!opened) {
                closed.set(true);
                releaseConnection(); // a log that could not be opened must not keep its connection
            }
        }
    }

    void runTransaction(Consumer<Connection> operation) {
        runTransaction(connection -> {
            operation.accept(connection);
            return null;
        });
    }

    <T> T runTransaction(Function<Connection, T> operation) {
        synchronized (sync) {

            boolean success = false;

            int N = 2;

            for (int i = 0; i < N; i++) {
                try {

                    if (theConnection == null) {
                        if (closed.get()) {
                            // e.g. an asynchronous write that runs after close(): never reopen without the lock
                            throw new RuntimeException(String.format("Saga-log is already closed, saga-log-id: %s", sagaLogId));
                        }
                        theConnection = dataSource.getConnection();
                    }

                    if (reconnecting) {
                        dataSource.evictConnection(theConnection);
                        theConnection = dataSource.getConnection();

                        if (lockIsHeld) {
                            // avoid race-condition where the advisory lock might or might not yet have been
                            // automatically released when previous connection was closed or evicted
                            unlock(theConnection, lockKey);
                        }

                        // re-acquire lock on new connection
                        if (!tryLock(theConnection, lockKey)) {
                            throw new SagaLogBusyException("Unable to re-acquire lock after connection retry.");
                        }

                        reconnecting = false;
                    }

                    theConnection.setAutoCommit(false);
                    theConnection.beginRequest();

                    T result = operation.apply(theConnection);

                    if (i < failOnXAttempts) {
                        theConnection.close();
                    }

                    theConnection.commit();
                    theConnection.endRequest();

                    success = true;

                    return result;

                } catch (SQLException e) {
                    if (i + 1 < N) {
                        reconnecting = true;
                        try {
                            if (theConnection != null) {
                                dataSource.evictConnection(theConnection);
                            }
                            theConnection = dataSource.getConnection();
                            if (lockIsHeld) {
                                // avoid race-condition where the advisory lock might or might not yet have been
                                // automatically released when previous connection was closed or evicted
                                unlock(theConnection, lockKey);
                            }

                            // re-acquire lock on new connection
                            boolean acquired = false;
                            for (int j = 0; j < 5; j++) {
                                if ((acquired = tryLock(theConnection, lockKey))) {
                                    break;
                                }
                                sync.wait(2000);
                            }
                            if (!acquired) {
                                throw new SagaLogBusyException("Unable to re-acquire lock after connection retry.", e);
                            }

                            reconnecting = false;
                        } catch (SQLException | InterruptedException ex) {
                            // reconnect fails, propagate original exception and ignore this exception
                            throw new RuntimeException(e);
                        }
                    } else {
                        LOG.warn(String.format("Retry limit reached after %d attempts while attempting to run transaction on sagalog '%s'", i + 1, sagaLogId.getLogName()), e);
                        throw new RuntimeException("Retry limit reached after " + (i + 1) + " attempts", e);
                    }
                } finally {
                    if (!success && theConnection != null) {
                        try {
                            theConnection.rollback();
                        } catch (SQLException e) {
                            // ignore to avoid masking first exception from other try block
                        }
                    }
                }
            }

            return null;
        }
    }

    private boolean tryLock(Connection connection, long key) {
        try {
            String sql = String.format("SELECT pg_try_advisory_lock(?)");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean obtained = rs.getBoolean(1);
                        if (obtained) {
                            lockIsHeld = true;
                        }
                        return obtained;
                    }
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean unlock(Connection connection, long key) {
        try {
            String sql = String.format("SELECT pg_advisory_unlock(?)");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean released = rs.getBoolean(1);
                        if (released) {
                            lockIsHeld = false;
                        }
                        return released;
                    }
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long getOrAssignLockKey(SagaLogId logId) {
        return runTransaction(connection -> {
            try {
                return getOrAssignLockKey(connection, logId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private long getOrAssignLockKey(Connection connection, SagaLogId logId) throws SQLException {
        Long existingLockKey = findExistingLock(connection, logId);
        if (existingLockKey != null) {
            return existingLockKey;
        }

        // assigned new lock
        long lockKey = findAvailableLockKey(connection);
        assignLockKeyToLog(connection, logId, lockKey);
        return lockKey;
    }

    private Long findExistingLock(Connection connection, SagaLogId logId) throws SQLException {
        String sql = String.format("SELECT lock_key FROM \"%s\".\"Locks\" WHERE namespace = ? AND instance_id = ? AND log_id = ?", ((PostgresSagaLogId) logId).getSchema());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sagaLogId.getNamespace());
            ps.setString(2, sagaLogId.getClusterInstanceId());
            ps.setString(3, logId.getLogName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Long lockKey = (Long) rs.getObject(1);
                    ps.close();
                    return lockKey;
                }
                ps.close();
                return null;
            }
        }
    }

    private long findAvailableLockKey(Connection connection) throws SQLException {
        String sql = String.format("SELECT 1 FROM \"%s\".\"Locks\" WHERE lock_key = ?", sagaLogId.getSchema());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < 10; i++) {
                long attemptedLockKey = 1 + random.nextInt(1000000000);
                ps.setLong(1, attemptedLockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return attemptedLockKey;
                    }
                }
            }
            throw new IllegalStateException("Unable to generate random lockKey that is available");
        }
    }

    private void assignLockKeyToLog(Connection connection, SagaLogId logId, long lockKey) throws SQLException {
        String sql = String.format("INSERT INTO \"%s\".\"Locks\" (namespace, instance_id, log_id, lock_key) VALUES(?, ?, ?, ?)", ((PostgresSagaLogId) logId).getSchema());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sagaLogId.getNamespace());
            ps.setString(2, sagaLogId.getClusterInstanceId());
            ps.setString(3, logId.getLogName());
            ps.setLong(4, lockKey);
            ps.executeUpdate();
        }
    }

    private void initSagalogTable() {
        runTransaction(connection -> {
            try {
                createSagalogTableIfNotExists(connection);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void createSagalogTableIfNotExists(Connection connection) throws SQLException {
        String sql = String.format("CREATE TABLE IF NOT EXISTS \"%s\".\"%s\" (\n" +
                "    entry_id        uuid      NOT NULL,\n" +
                "    txid            varchar   NOT NULL,\n" +
                "    entry_type      smallint  NOT NULL,\n" +
                "    node_id         varchar   NOT NULL,\n" +
                "    saga_name       varchar   NULL,\n" +
                "    data            text      NULL,\n" +
                "    PRIMARY KEY (entry_id, txid)\n" +
                ")", sagaLogId.getSchema(), sagaLogId.getTableName());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    public UUID generateId() {
        UUID previousId = prevId.get();
        UUID next = UuidV7.next(previousId);
        while (!prevId.compareAndSet(previousId, next)) {
            Thread.yield();
            previousId = prevId.get();
            next = UuidV7.next(previousId);
        }
        return next;
    }

    @Override
    public SagaLogId id() {
        return sagaLogId;
    }

    @Override
    public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> {
            UUID entryId = generateId();
            SagaLogEntry entry = builder
                    .id(new PostgresSagaLogEntryId(entryId))
                    .build();
            runTransaction(connection -> {
                try {
                    String sql = String.format("INSERT INTO \"%s\".\"%s\" (txid, entry_id, entry_type, node_id, saga_name, data) VALUES(?, ?, ?, ?, ?, ?)", sagaLogId.getSchema(), sagaLogId.getTableName());
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setString(1, entry.getExecutionId());
                        ps.setObject(2, entryId);
                        ps.setShort(3, PostgresSagaTools.toShort(entry.getEntryType()));
                        ps.setString(4, entry.getNodeId());
                        ps.setString(5, entry.getSagaName());
                        // untyped, so the server converts to the column's type: text, or json in tables made before 2.0.0
                        ps.setObject(6, entry.getJsonData(), Types.OTHER);
                        ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return entry;
        });
    }

    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId _entryId) {
        checkNotClosed();
        return CompletableFuture.runAsync(() -> runTransaction(connection -> {
            try {
                String sql = String.format("DELETE FROM \"%s\".\"%s\" WHERE entry_id <= ?", sagaLogId.getSchema(), sagaLogId.getTableName());
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    PostgresSagaLogEntryId entryId = (PostgresSagaLogEntryId) _entryId;
                    ps.setObject(1, entryId.id);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Override
    public CompletableFuture<Void> truncate() {
        checkNotClosed();
        return CompletableFuture.runAsync(() -> runTransaction(connection -> {
            try {
                String sql = String.format("TRUNCATE TABLE \"%s\".\"%s\"", sagaLogId.getSchema(), sagaLogId.getTableName());
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(sql);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        checkNotClosed();
        return runTransaction(connection -> {
            try {
                String sql = String.format("SELECT entry_id, txid, entry_type, node_id, saga_name, data FROM \"%s\".\"%s\" ORDER BY entry_id", sagaLogId.getSchema(), sagaLogId.getTableName());
                try (Statement statement = connection.createStatement()) {
                    try (ResultSet rs = statement.executeQuery(sql)) {
                        List<SagaLogEntry> result = new ArrayList<>();
                        while (rs.next()) {
                            SagaLogEntry entry = sagaLogEntryRowMapper(rs);
                            result.add(entry);
                        }
                        return result.stream();
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Stream<SagaLogEntry> readEntries(String txId) {
        checkNotClosed();
        return runTransaction(connection ->
        {
            try {
                String sql = String.format("SELECT entry_id, txid, entry_type, node_id, saga_name, data FROM \"%s\".\"%s\" WHERE txid = ? ORDER BY entry_id", sagaLogId.getSchema(), sagaLogId.getTableName());
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, txId);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<SagaLogEntry> result = new ArrayList<>();
                        while (rs.next()) {
                            SagaLogEntry entry = sagaLogEntryRowMapper(rs);
                            result.add(entry);
                        }
                        return result.stream();
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private SagaLogEntry sagaLogEntryRowMapper(ResultSet rs) throws SQLException {
        UUID entry_id = (UUID) rs.getObject("entry_id");
        String txid = rs.getString("txid");
        short entry_type = rs.getShort("entry_type");
        String node_id = rs.getString("node_id");
        String saga_name = rs.getString("saga_name");
        String data = rs.getString("data");
        PostgresSagaLogEntryId entryId = new PostgresSagaLogEntryId(entry_id);
        SagaLogEntryType entryType = PostgresSagaTools.fromShort(entry_type);
        return new SagaLogEntryBuilder()
                .executionId(txid)
                .id(entryId)
                .entryType(entryType)
                .nodeId(node_id)
                .sagaName(saga_name)
                .jsonData(data)
                .build();
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new RuntimeException(String.format("Saga-log is already closed, saga-log-id: %s", sagaLogId));
        }
    }

    @Override
    public String toString(SagaLogEntryId id) {
        return ((PostgresSagaLogEntryId) id).id.toString();
    }

    @Override
    public PostgresSagaLogEntryId fromString(String id) {
        return new PostgresSagaLogEntryId(UUID.fromString(id));
    }

    @Override
    public byte[] toBytes(SagaLogEntryId id) {
        return UuidV7.toBytes(((PostgresSagaLogEntryId) id).id);
    }

    @Override
    public SagaLogEntryId fromBytes(byte[] idBytes) {
        return new PostgresSagaLogEntryId(UuidV7.fromBytes(idBytes));
    }

    /**
     * Releases the advisory lock and gives the connection back to the data source. The unlock runs in a transaction
     * that is committed, so the connection is not left idle in a transaction.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseConnection();
        }
    }

    private void releaseConnection() {
        synchronized (sync) {
            if (theConnection == null) {
                return;
            }
            try {
                if (lockIsHeld) {
                    unlock(theConnection, lockKey);
                }
                if (!theConnection.getAutoCommit()) {
                    theConnection.commit();
                }
            } catch (SQLException | RuntimeException e) {
                LOG.warn(String.format("Unable to release the lock of sagalog '%s' cleanly; closing its connection", sagaLogId.getLogName()), e);
            } finally {
                try {
                    theConnection.close(); // back to the pool; a session-level advisory lock ends with the session anyway
                } catch (SQLException e) {
                    LOG.warn(String.format("Unable to close the connection of sagalog '%s'", sagaLogId.getLogName()), e);
                }
                theConnection = null;
                lockIsHeld = false;
            }
        }
    }
}
