package com.cotp.jdbc;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Internal JDBC helpers.
 */
final class JdbcUtil {

    private JdbcUtil() {
    }

    /**
     * Best-effort detection of a unique/primary-key constraint violation across drivers: either the
     * standard {@link SQLIntegrityConstraintViolationException} or an SQLState in class {@code 23}
     * (integrity constraint violation).
     */
    static boolean isConstraintViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
