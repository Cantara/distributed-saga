package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The database session of one open saga log: one connection, which holds the log's advisory lock, and the policy for
 * a lost connection.
 * <p>
 * Every operation runs in a transaction on that connection, under the session's monitor. When a statement or the
 * commit fails because the connection was lost (SQLState class {@code 08}, or {@code 57P01}..{@code 57P03}), the
 * session evicts the connection, gets a new one, takes the advisory lock again, and checks the ownership token in
 * {@code Locks}. If another process took the log in between, the log is lost for good: the operation and every later
 * one fail with {@link SagaLogBusyException}. Otherwise the operation runs once more. Any other failure is not retried.
 * <p>
 * While it waits for the lock, the session sleeps holding its monitor, so no other operation of the log can run on a
 * half-made session.
 */
final class LogSession {

    private static final Logger LOG = LoggerFactory.getLogger(LogSession.class);

    /** How long a reconnect waits for the advisory lock, which a lost session may hold until the server notices. */
    static final Duration DEFAULT_RELOCK_WAIT = Duration.ofSeconds(10);

    private static final long FIRST_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long MAX_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(1);

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final PostgresDataSource dataSource;
    private final PostgresSagaLogId id;
    private final Random random;
    private final long relockWaitNanos;
    private final String resource;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // guarded by this
    private Connection connection; // null after a lost connection, until the next operation reconnects
    private long lockKey;
    private long token;
    private boolean ownershipLost;

    LogSession(PostgresDataSource dataSource, PostgresSagaLogId id, Random random, Duration relockWait) {
        this.dataSource = dataSource;
        this.id = id;
        this.random = random;
        this.relockWaitNanos = relockWait.toNanos();
        this.resource = "saga log " + id.getSchema() + "." + id.getTableName();
    }

    /**
     * Takes the log: assigns its lock key if it has none, takes the advisory lock, writes a new ownership token, and
     * runs {@code setup} (the table) in the same transaction. Retried once on a lost connection.
     *
     * @throws SagaLogBusyException     if another process holds the log
     * @throws PostgresSagaLogException if the database fails
     */
    synchronized void open(SqlWork<Void> setup) {
        SQLException first = null;
        while (true) {
            try {
                connection = newConnection();
                lockKey = LockRegistry.keyFor(connection, id, random);
                connection.commit();
                if (!LockRegistry.tryLock(connection, lockKey)) {
                    throw new SagaLogBusyException(String.format("Unable to acquire exclusive lock on %s with lock-key %d.", id, lockKey));
                }
                Long claimed = LockRegistry.claim(connection, id, random);
                if (claimed == null) {
                    throw new SagaLogBusyException(String.format("Unable to open %s: it was deleted by another process while it was opened.", id));
                }
                token = claimed;
                setup.run(connection);
                connection.commit();
                return;
            } catch (SQLException e) {
                if (first == null && isConnectionLoss(e)) {
                    evict();
                    first = e;
                    continue;
                }
                closed.set(true);
                releaseQuietly(e);
                throw translate("open", first != null ? first : e, first != null ? e : null);
            } catch (RuntimeException | Error e) {
                closed.set(true);
                releaseQuietly(e);
                if (first != null && e.getCause() != first) {
                    e.addSuppressed(first);
                }
                throw e;
            }
        }
    }

    /**
     * Runs {@code work} in a transaction on the log's connection, and commits it.
     *
     * @throws IllegalStateException    if the log is closed
     * @throws SagaLogBusyException     if another process took the log after its connection was lost, or holds it
     * @throws PostgresSagaLogException if the database fails
     */
    synchronized <T> T run(String operation, SqlWork<T> work) {
        checkNotClosed();
        if (ownershipLost) {
            throw ownershipLost(operation, null);
        }
        SQLException first = null;
        while (true) {
            try {
                if (connection == null) {
                    reconnect(operation, first);
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                if (first == null && isConnectionLoss(e)) {
                    evict();
                    first = e;
                    continue;
                }
                if (isConnectionLoss(e)) {
                    evict();
                } else {
                    rollbackQuietly(e);
                }
                throw translate(operation, first != null ? first : e, first != null ? e : null);
            } catch (RuntimeException | Error e) {
                rollbackQuietly(e);
                if (first != null && e.getCause() != first) {
                    e.addSuppressed(first);
                }
                throw e;
            }
        }
    }

    /**
     * A new connection, the advisory lock again, and the ownership token checked. Called with the monitor held.
     */
    private void reconnect(String operation, SQLException cause) throws SQLException {
        Connection c = newConnection();
        connection = c;
        long deadline = System.nanoTime() + relockWaitNanos;
        long pause = FIRST_PAUSE_NANOS;
        while (!LockRegistry.tryLock(c, lockKey)) {
            c.rollback(); // not idle in a transaction while waiting
            if (System.nanoTime() + pause > deadline) {
                releaseQuietly(null);
                SagaLogBusyException busy = new SagaLogBusyException(String.format("%s on %s: unable to take its lock back within %d ms"
                                + " after its connection was lost; another process holds it", operation, resource,
                        TimeUnit.NANOSECONDS.toMillis(relockWaitNanos)), cause);
                throw busy;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(pause); // holding the monitor: no other operation runs meanwhile
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseQuietly(null);
                PostgresSagaLogException interrupted = new PostgresSagaLogException(operation + " (interrupted while reconnecting)",
                        resource, cause != null ? cause : e);
                if (cause != null) {
                    interrupted.addSuppressed(e);
                }
                throw interrupted;
            }
            pause = Math.min(pause * 2, MAX_PAUSE_NANOS);
        }
        Long current = LockRegistry.token(c, id);
        c.commit();
        if (current == null || current != token) {
            ownershipLost = true;
            releaseQuietly(null);
            throw ownershipLost(operation, cause);
        }
    }

    private SagaLogBusyException ownershipLost(String operation, Throwable cause) {
        return new SagaLogBusyException(String.format("%s on %s: another process held the log after this log's connection was"
                + " lost. Remove the log from the pool and connect it again.", operation, resource), cause);
    }

    private Connection newConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false);
            return c;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * @return true if the connection is gone: the statement or commit never reached the server, or its reply was lost
     */
    static boolean isConnectionLoss(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return false; // no connection could be had from the pool: retrying waits the same timeout again
            }
            if (t instanceof SQLNonTransientConnectionException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")) {
                    return true;
                }
            }
        }
        return false;
    }

    void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException(String.format("Saga-log is already closed, saga-log-id: %s", id));
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * Releases the advisory lock and gives the connection back to the pool, with no lock and no open transaction. A
     * connection that cannot be cleaned up is evicted, which ends its session and so its locks.
     */
    void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (this) {
                releaseQuietly(null);
            }
        }
    }

    private void releaseQuietly(Throwable primary) {
        Connection c = connection;
        if (c == null) {
            return;
        }
        connection = null;
        try {
            c.rollback();
            LockRegistry.unlockAll(c);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            if (primary != null) {
                primary.addSuppressed(e);
            } else {
                LOG.warn("Unable to release the lock of {} cleanly; evicting its connection", resource, e);
            }
            dataSource.evictConnection(c);
            return;
        }
        try {
            c.close();
        } catch (SQLException e) {
            LOG.warn("Unable to give back the connection of {}", resource, e);
        }
    }

    private void evict() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            dataSource.evictConnection(c);
        }
    }

    private void rollbackQuietly(Throwable primary) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            primary.addSuppressed(e);
            if (isConnectionLoss(e)) {
                evict();
            }
        }
    }

    private PostgresSagaLogException translate(String operation, SQLException first, SQLException later) {
        PostgresSagaLogException e = new PostgresSagaLogException(operation, resource, first);
        if (later != null) {
            e.addSuppressed(later);
        }
        return e;
    }
}
