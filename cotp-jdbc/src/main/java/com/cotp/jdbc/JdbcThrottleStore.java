package com.cotp.jdbc;

import com.cotp.CotpException;
import com.cotp.ThrottleStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * {@link ThrottleStore} backed by a JDBC {@link DataSource}.
 *
 * <p>{@link #incrementFailure(String)} uses an atomic {@code UPDATE ... SET failures = failures + 1}
 * (with insert-on-missing), so concurrent failures are not lost. See {@link JdbcStores} for the
 * schema.</p>
 */
public final class JdbcThrottleStore implements ThrottleStore {

    private static final String SQL_FAILURES =
            "SELECT failures FROM cotp_throttle WHERE card_id = ?";
    private static final String SQL_LOCKED_UNTIL =
            "SELECT locked_until FROM cotp_throttle WHERE card_id = ?";
    private static final String SQL_BUMP =
            "UPDATE cotp_throttle SET failures = failures + 1, last_access = ? WHERE card_id = ?";
    private static final String SQL_INSERT_ROW =
            "INSERT INTO cotp_throttle (card_id, failures, locked_until, last_access) VALUES (?, ?, ?, ?)";
    private static final String SQL_SET_LOCK =
            "UPDATE cotp_throttle SET locked_until = ?, last_access = ? WHERE card_id = ?";
    private static final String SQL_DELETE = "DELETE FROM cotp_throttle WHERE card_id = ?";
    private static final String SQL_EVICT_IDLE =
            "DELETE FROM cotp_throttle WHERE (locked_until IS NULL OR locked_until <= ?)"
                    + " AND (last_access IS NULL OR last_access <= ?)";

    /** Gate for the best-effort {@link #evictIdle(Instant, Duration)} sweep on the hot path. */
    private static final int EVICT_GATE = 1024;

    private final DataSource dataSource;
    private final AtomicLong evictCounter = new AtomicLong();

    public JdbcThrottleStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public int failureCount(String cardId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FAILURES)) {
            ps.setString(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new CotpException("failureCount failed", e);
        }
    }

    @Override
    public Instant lockedUntil(String cardId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_LOCKED_UNTIL)) {
            ps.setString(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long value = rs.getLong(1);
                return rs.wasNull() ? null : Instant.ofEpochMilli(value);
            }
        } catch (SQLException e) {
            throw new CotpException("lockedUntil failed", e);
        }
    }

    @Override
    public void lockUntil(String cardId, Instant until) {
        Objects.requireNonNull(until, "until");
        long millis = until.toEpochMilli();
        try (Connection c = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(SQL_SET_LOCK)) {
                ps.setLong(1, millis);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, cardId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_ROW)) {
                    ps.setString(1, cardId);
                    ps.setInt(2, 0);
                    ps.setLong(3, millis);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!JdbcUtil.isConstraintViolation(e)) {
                        throw e;
                    }
                    // Raced with another insert: apply the lock.
                    try (PreparedStatement ps = c.prepareStatement(SQL_SET_LOCK)) {
                        ps.setLong(1, millis);
                        ps.setLong(2, System.currentTimeMillis());
                        ps.setString(3, cardId);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new CotpException("lockUntil failed", e);
        }
    }

    @Override
    public int incrementFailure(String cardId) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(SQL_BUMP)) {
                ps.setLong(1, now);
                ps.setString(2, cardId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_ROW)) {
                    ps.setString(1, cardId);
                    ps.setInt(2, 1);
                    ps.setNull(3, java.sql.Types.BIGINT);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!JdbcUtil.isConstraintViolation(e)) {
                        throw e;
                    }
                    try (PreparedStatement ps = c.prepareStatement(SQL_BUMP)) {
                        ps.setLong(1, now);
                        ps.setString(2, cardId);
                        ps.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(SQL_FAILURES)) {
                ps.setString(1, cardId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            throw new CotpException("incrementFailure failed", e);
        }
    }

    @Override
    public void reset(String cardId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, cardId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new CotpException("reset failed", e);
        }
    }

    @Override
    public void evictIdle(Instant now, Duration idleTtl) {
        // Best-effort: avoid a DELETE on every verification; call purge(now, idleTtl) for an
        // unconditional sweep (e.g. from a scheduled task).
        if (now == null || idleTtl == null || (evictCounter.incrementAndGet() & (EVICT_GATE - 1)) != 0) {
            return;
        }
        purge(now, idleTtl);
    }

    /**
     * Unconditionally removes idle throttle rows (not locked and not accessed within {@code idleTtl}).
     * Safe to call from a scheduled task.
     */
    public void purge(Instant now, Duration idleTtl) {
        if (now == null || idleTtl == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_EVICT_IDLE)) {
            ps.setLong(1, now.toEpochMilli());
            ps.setLong(2, now.minus(idleTtl).toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new CotpException("evictIdle failed", e);
        }
    }
}
