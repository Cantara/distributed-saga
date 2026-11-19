package no.cantara.sagalog.postgres;

import java.sql.SQLException;

/**
 * A database operation of the PostgreSQL saga log failed. The message names the operation, the saga log (or the
 * schema, for an operation of the pool), and the database's message and SQLState; the cause is the
 * {@link SQLException}, kept as it was.
 * <p>
 * A log that is held by another process fails with {@link no.cantara.sagalog.SagaLogBusyException} instead, and a log
 * used after it was closed with {@link IllegalStateException}.
 */
public class PostgresSagaLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final String sqlState;

    PostgresSagaLogException(String operation, String resource, Throwable cause) {
        super(message(operation, resource, cause), cause);
        this.operation = operation;
        this.sqlState = cause instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static String message(String operation, String resource, Throwable cause) {
        StringBuilder message = new StringBuilder(operation).append(" failed on ").append(resource);
        if (cause != null) {
            message.append(": ").append(cause.getMessage());
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                message.append(" (SQLState ").append(sql.getSQLState()).append(')');
            }
        }
        return message.toString();
    }

    /**
     * @return the operation that failed, for example {@code write} or {@code open}
     */
    public String getOperation() {
        return operation;
    }

    /**
     * @return the SQLState of the cause, or null
     */
    public String getSqlState() {
        return sqlState;
    }
}
