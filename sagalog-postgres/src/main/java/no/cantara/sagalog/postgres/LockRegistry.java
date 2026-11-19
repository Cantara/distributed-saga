package no.cantara.sagalog.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

/**
 * The {@code Locks} table and the advisory locks it names: one row per log, with the advisory-lock key assigned to the
 * log on its first open, and the ownership token of the process that took the lock last.
 * <p>
 * The token fences a log against a lost session. Whoever takes a log's advisory lock writes a new random token while
 * it holds the lock. A log whose session ended takes the lock back on a new session and reads the token: if it is not
 * the one it wrote, another process held the log in between, and the log must not be written on.
 */
final class LockRegistry {

    private LockRegistry() {
    }

    /**
     * Adds the token column to a {@code Locks} table made before it existed (2.1.0 and earlier). Idempotent; the table
     * is altered only when the column is missing.
     */
    static void migrate(Connection connection, String schema) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'Locks' AND column_name = 'owner_token'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + PostgresSagaLogId.locksTable(schema) + " ADD COLUMN IF NOT EXISTS owner_token bigint NOT NULL DEFAULT 0");
        }
    }

    /**
     * @return the log's lock key, assigning a free one if the log has none. Two processes that open a new log at once
     * get the same key.
     */
    static long keyFor(Connection connection, PostgresSagaLogId id, Random random) throws SQLException {
        Long existing = findKey(connection, id);
        if (existing != null) {
            return existing;
        }
        long candidate = findAvailableKey(connection, id, random);
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + id.qualifiedLocksTableName()
                + " (namespace, instance_id, log_id, lock_key) VALUES (?, ?, ?, ?) ON CONFLICT (namespace, instance_id, log_id) DO NOTHING")) {
            bindId(ps, id);
            ps.setLong(4, candidate);
            ps.executeUpdate();
        }
        Long key = findKey(connection, id); // ours, or the one another process inserted first
        if (key == null) {
            throw new SQLException("The lock key of " + id + " was deleted while it was assigned", "55000");
        }
        return key;
    }

    static Long findKey(Connection connection, PostgresSagaLogId id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT lock_key FROM " + id.qualifiedLocksTableName()
                + " WHERE namespace = ? AND instance_id = ? AND log_id = ?")) {
            bindId(ps, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long findAvailableKey(Connection connection, PostgresSagaLogId id, Random random) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + id.qualifiedLocksTableName() + " WHERE lock_key = ?")) {
            for (int i = 0; i < 10; i++) {
                long attemptedLockKey = 1 + random.nextInt(1000000000);
                ps.setLong(1, attemptedLockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return attemptedLockKey;
                    }
                }
            }
        }
        throw new IllegalStateException("Unable to generate random lockKey that is available");
    }

    /**
     * Writes a new ownership token. Call only while holding the log's advisory lock.
     *
     * @return the token, or null if the log has no row (it was deleted)
     */
    static Long claim(Connection connection, PostgresSagaLogId id, Random random) throws SQLException {
        long token;
        do {
            token = random.nextLong();
        } while (token == 0); // 0 is the token of a row made before tokens existed
        try (PreparedStatement ps = connection.prepareStatement("UPDATE " + id.qualifiedLocksTableName()
                + " SET owner_token = ? WHERE namespace = ? AND instance_id = ? AND log_id = ?")) {
            ps.setLong(1, token);
            ps.setString(2, id.getNamespace());
            ps.setString(3, id.getClusterInstanceId());
            ps.setString(4, id.getLogName());
            return ps.executeUpdate() == 1 ? token : null;
        }
    }

    /**
     * @return the current ownership token, or null if the log has no row (it was deleted)
     */
    static Long token(Connection connection, PostgresSagaLogId id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT owner_token FROM " + id.qualifiedLocksTableName()
                + " WHERE namespace = ? AND instance_id = ? AND log_id = ?")) {
            bindId(ps, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    static void delete(Connection connection, PostgresSagaLogId id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + id.qualifiedLocksTableName()
                + " WHERE namespace = ? AND instance_id = ? AND log_id = ?")) {
            bindId(ps, id);
            ps.executeUpdate();
        }
    }

    static boolean tryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Releases every advisory lock of the session, whatever the count, so a connection given back to the pool holds
     * none.
     */
    static void unlockAll(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT pg_advisory_unlock_all()");
        }
    }

    private static void bindId(PreparedStatement ps, PostgresSagaLogId id) throws SQLException {
        ps.setString(1, id.getNamespace());
        ps.setString(2, id.getClusterInstanceId());
        ps.setString(3, id.getLogName());
    }
}
