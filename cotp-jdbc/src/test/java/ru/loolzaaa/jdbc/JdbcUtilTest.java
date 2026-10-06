package ru.loolzaaa.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;

class JdbcUtilTest {

    @Test
    void detectsIntegrityInstance() {
        assertTrue(JdbcUtil.isConstraintViolation(
                new SQLIntegrityConstraintViolationException("dup")));
    }

    @Test
    void detectsIntegritySqlStateInPrimary() {
        assertTrue(JdbcUtil.isConstraintViolation(new SQLException("dup", "23505")));
    }

    @Test
    void detectsIntegritySqlStateInNextException() {
        SQLException primary = new SQLException("batch", "42000");
        primary.setNextException(new SQLException("unique", "23505"));
        assertTrue(JdbcUtil.isConstraintViolation(primary));
    }

    @Test
    void nonConstraintIsNotFlagged() {
        assertFalse(JdbcUtil.isConstraintViolation(new SQLException("syntax", "42000")));
        assertFalse(JdbcUtil.isConstraintViolation(new SQLException("none", (String) null)));

        SQLException primary = new SQLException("batch", "42000");
        primary.setNextException(new SQLException("also not", "42000"));
        assertFalse(JdbcUtil.isConstraintViolation(primary));
    }
}
