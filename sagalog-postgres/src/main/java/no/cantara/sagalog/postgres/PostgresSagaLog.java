package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryId;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogId;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A saga log in one table. The connection, the advisory lock and the retry are the {@link LogSession}'s; this class
 * holds the entry SQL.
 * <p>
 * Every operation runs on the calling thread. {@code write} and {@code truncate} return a future that is already
 * complete: the log serialises its operations on one connection, so running them elsewhere would only add a thread
 * hop, and the engine joins every write at once.
 */
class PostgresSagaLog implements SagaLog, AutoCloseable {

    private static final String COLUMNS = "entry_id, txid, entry_type, node_id, saga_name, data";

    final AtomicReference<UUID> prevId = new AtomicReference<>(UuidV7.generate());

    final PostgresSagaLogId sagaLogId;
    final LogSession session;

    private final String insertSql;
    private final String storedEntrySql;
    private final String truncateUpToSql;
    private final String truncateSql;
    private final String readAllSql;
    private final String readExecutionSql;

    PostgresSagaLog(PostgresDataSource dataSource, SagaLogId sagaLogId, Random random) {
        this(dataSource, sagaLogId, random, LogSession.DEFAULT_RELOCK_WAIT);
    }

    /**
     * Opens the log: takes its advisory lock, and creates its table and index if missing.
     *
     * @throws no.cantara.sagalog.SagaLogBusyException if another process holds the log
     * @throws PostgresSagaLogException               if the database fails
     */
    PostgresSagaLog(PostgresDataSource dataSource, SagaLogId sagaLogId, Random random, Duration relockWait) {
        this.sagaLogId = (PostgresSagaLogId) sagaLogId;
        String table = this.sagaLogId.qualifiedTableName();
        this.insertSql = "INSERT INTO " + table + " (txid, entry_id, entry_type, node_id, saga_name, data) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (entry_id, txid) DO NOTHING";
        this.storedEntrySql = "SELECT " + COLUMNS + " FROM " + table + " WHERE entry_id = ? AND txid = ?";
        this.truncateUpToSql = "DELETE FROM " + table + " WHERE entry_id <= ?";
        this.truncateSql = "TRUNCATE TABLE " + table;
        this.readAllSql = "SELECT " + COLUMNS + " FROM " + table + " ORDER BY entry_id";
        this.readExecutionSql = "SELECT " + COLUMNS + " FROM " + table + " WHERE txid = ? ORDER BY entry_id";
        this.session = new LogSession(dataSource, this.sagaLogId, random, relockWait);
        session.open(this::createTableAndIndexIfNotExist);
    }

    private Void createTableAndIndexIfNotExist(Connection connection) throws SQLException {
        String table = sagaLogId.qualifiedTableName();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        entry_id        uuid      NOT NULL,
                        txid            varchar   NOT NULL,
                        entry_type      smallint  NOT NULL,
                        node_id         varchar   NOT NULL,
                        saga_name       varchar   NULL,
                        data            text      NULL,
                        PRIMARY KEY (entry_id, txid)
                    )""".formatted(table));
            // readEntries(executionId); also made on open for tables made before the index existed
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + PostgresSagaLogId.quote(indexName()) + " ON " + table + " (txid, entry_id)");
        }
        return null;
    }

    /**
     * @return {@code <table>_txid}, or a name derived from the table name's hash when that would pass 63 bytes, so two
     * long table names never share an index name
     */
    String indexName() {
        String tableName = sagaLogId.getTableName();
        String name = tableName + "_txid";
        if (name.length() <= PostgresSagaLogId.MAX_IDENTIFIER_BYTES) {
            return name;
        }
        return "SAGALOG_txid_" + UUID.nameUUIDFromBytes(tableName.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
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

    /**
     * Writes the entry on the calling thread.
     *
     * @return a future that is complete: with the entry once it is committed, or with the failure
     * @throws IllegalStateException if the log is closed
     */
    @Override
    public CompletableFuture<SagaLogEntry> write(SagaLogEntryBuilder builder) {
        session.checkNotClosed();
        try {
            UUID entryId = generateId();
            SagaLogEntry entry = builder
                    .id(new PostgresSagaLogEntryId(entryId))
                    .build();
            session.run("write", connection -> insert(connection, entryId, entry));
            return CompletableFuture.completedFuture(entry);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Inserts the entry. A retry after a lost commit acknowledgement finds the entry already stored: that is success
     * when the stored row is this entry.
     */
    private Void insert(Connection connection, UUID entryId, SagaLogEntry entry) throws SQLException {
        short entryType = PostgresSagaTools.toShort(entry.getEntryType());
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setString(1, entry.getExecutionId());
            ps.setObject(2, entryId);
            ps.setShort(3, entryType);
            ps.setString(4, entry.getNodeId());
            ps.setString(5, entry.getSagaName());
            // untyped, so the server converts to the column's type: text, or json in tables made before 2.0.0
            ps.setObject(6, entry.getJsonData(), Types.OTHER);
            if (ps.executeUpdate() == 1) {
                return null;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(storedEntrySql)) {
            ps.setObject(1, entryId);
            ps.setString(2, entry.getExecutionId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && sameRow(sagaLogEntryRowMapper(rs), entry)) {
                    return null; // stored by the attempt whose acknowledgement was lost
                }
            }
        }
        throw new SQLException("A different entry with id " + entryId + " and execution id " + entry.getExecutionId()
                + " is already stored", "23505");
    }

    private static boolean sameRow(SagaLogEntry stored, SagaLogEntry entry) {
        return stored.equals(entry) // the id and the execution id
                && stored.getEntryType() == entry.getEntryType()
                && Objects.equals(stored.getNodeId(), entry.getNodeId())
                && Objects.equals(stored.getSagaName(), entry.getSagaName())
                && Objects.equals(stored.getJsonData(), entry.getJsonData());
    }

    /**
     * @return a future that is complete: once the entries are deleted, or with the failure
     * @throws IllegalStateException if the log is closed
     */
    @Override
    public CompletableFuture<Void> truncate(SagaLogEntryId _entryId) {
        session.checkNotClosed();
        try {
            PostgresSagaLogEntryId entryId = (PostgresSagaLogEntryId) _entryId;
            session.run("truncate", connection -> {
                try (PreparedStatement ps = connection.prepareStatement(truncateUpToSql)) {
                    ps.setObject(1, entryId.id);
                    ps.executeUpdate();
                }
                return null;
            });
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * @return a future that is complete: once the table is truncated, or with the failure
     * @throws IllegalStateException if the log is closed
     */
    @Override
    public CompletableFuture<Void> truncate() {
        session.checkNotClosed();
        try {
            session.run("truncate", connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(truncateSql);
                }
                return null;
            });
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Reads the whole log into memory, completed executions included, holding the log's connection meanwhile; keep
     * logs short by truncating them.
     */
    @Override
    public Stream<SagaLogEntry> readIncompleteSagas() {
        session.checkNotClosed();
        return session.run("readIncompleteSagas", connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(readAllSql)) {
                return readAll(rs).stream();
            }
        });
    }

    @Override
    public Stream<SagaLogEntry> readEntries(String txId) {
        session.checkNotClosed();
        return session.run("readEntries", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(readExecutionSql)) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    return readAll(rs).stream();
                }
            }
        });
    }

    private List<SagaLogEntry> readAll(ResultSet rs) throws SQLException {
        List<SagaLogEntry> result = new ArrayList<>();
        while (rs.next()) {
            result.add(sagaLogEntryRowMapper(rs));
        }
        return result;
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

    boolean isClosed() {
        return session.isClosed();
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
     * Releases the advisory lock and gives the connection back to the data source, with no lock and no transaction
     * left on it; a connection that cannot be cleaned up is evicted instead. Never throws; idempotent.
     */
    @Override
    public void close() {
        session.close();
    }
}
