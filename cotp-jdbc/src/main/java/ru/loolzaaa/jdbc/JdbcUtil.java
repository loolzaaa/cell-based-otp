package ru.loolzaaa.jdbc;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Internal JDBC helpers.
 */
final class JdbcUtil {

    private JdbcUtil() {
    }

    /**
     * Best-effort detection of a unique/primary-key constraint violation across drivers. It inspects
     * the {@code getNextException()} chain (where some drivers report the real SQLState) as well as
     * the {@code getCause()} chain, accepting either the standard
     * {@link SQLIntegrityConstraintViolationException} or an SQLState in class {@code 23}.
     */
    static boolean isConstraintViolation(SQLException e) {
        for (SQLException sql = e; sql != null; sql = sql.getNextException()) {
            if (isViolation(sql)) {
                return true;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sql && hasIntegrityState(sql)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isViolation(SQLException e) {
        return e instanceof SQLIntegrityConstraintViolationException || hasIntegrityState(e);
    }

    private static boolean hasIntegrityState(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }
}
