package com.cotp.jdbc;

import com.cotp.BurnStore;
import com.cotp.CotpException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * {@link BurnStore} backed by a JDBC {@link DataSource}.
 *
 * <p>Atomicity relies on the primary keys / unique constraints: a duplicate insert means the cell or
 * challenge was already consumed and is reported as {@code false}. Timestamps are stored as epoch
 * milliseconds. Intended for multi-node deployments; see {@link JdbcStores} for the schema.</p>
 */
public final class JdbcBurnStore implements BurnStore {

    private static final String SQL_IS_CELL_BURNED =
            "SELECT 1 FROM cotp_cell_burn WHERE card_id = ? AND cell_id = ?";
    private static final String SQL_BURN_CELL =
            "INSERT INTO cotp_cell_burn (card_id, cell_id, burned_at) VALUES (?, ?, ?)";
    private static final String SQL_LAST_CELL_USE =
            "SELECT last_used_at FROM cotp_cell_use WHERE card_id = ? AND cell_id = ?";
    private static final String SQL_INSERT_CELL_USE =
            "INSERT INTO cotp_cell_use (card_id, cell_id, last_used_at) VALUES (?, ?, ?)";
    private static final String SQL_UPDATE_CELL_USE =
            "UPDATE cotp_cell_use SET last_used_at = ? WHERE card_id = ? AND cell_id = ?";
    private static final String SQL_IS_CHALLENGE_USED =
            "SELECT 1 FROM cotp_challenge WHERE challenge_id = ?";
    private static final String SQL_INSERT_CHALLENGE =
            "INSERT INTO cotp_challenge (challenge_id, card_id, expires_at) VALUES (?, ?, ?)";
    private static final String SQL_BURNED_CELLS =
            "SELECT cell_id FROM cotp_cell_burn WHERE card_id = ?";
    private static final String SQL_DELETE_CELL_BURN = "DELETE FROM cotp_cell_burn WHERE card_id = ?";
    private static final String SQL_DELETE_CELL_USE = "DELETE FROM cotp_cell_use WHERE card_id = ?";
    private static final String SQL_DELETE_CHALLENGES = "DELETE FROM cotp_challenge WHERE card_id = ?";
    private static final String SQL_EVICT = "DELETE FROM cotp_challenge WHERE expires_at <= ?";

    /** Gate for the best-effort {@link #evictExpired(Instant)} sweep on the hot verification path. */
    private static final int EVICT_GATE = 1024;

    private final DataSource dataSource;
    private final AtomicLong evictCounter = new AtomicLong();

    public JdbcBurnStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean isCellBurned(String cardId, String cellId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_IS_CELL_BURNED)) {
            ps.setString(1, cardId);
            ps.setString(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new CotpException("isCellBurned failed", e);
        }
    }

    @Override
    public boolean markCellBurned(String cardId, String cellId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_BURN_CELL)) {
            ps.setString(1, cardId);
            ps.setString(2, cellId);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (JdbcUtil.isConstraintViolation(e)) {
                return false;
            }
            throw new CotpException("markCellBurned failed", e);
        }
    }

    @Override
    public Instant lastCellUse(String cardId, String cellId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_LAST_CELL_USE)) {
            ps.setString(1, cardId);
            ps.setString(2, cellId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Instant.ofEpochMilli(rs.getLong(1)) : null;
            }
        } catch (SQLException e) {
            throw new CotpException("lastCellUse failed", e);
        }
    }

    @Override
    public void recordCellUse(String cardId, String cellId, Instant at) {
        Objects.requireNonNull(at, "at");
        long millis = at.toEpochMilli();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_CELL_USE)) {
                ps.setString(1, cardId);
                ps.setString(2, cellId);
                ps.setLong(3, millis);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!JdbcUtil.isConstraintViolation(e)) {
                    throw e;
                }
                try (PreparedStatement ps = c.prepareStatement(SQL_UPDATE_CELL_USE)) {
                    ps.setLong(1, millis);
                    ps.setString(2, cardId);
                    ps.setString(3, cellId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new CotpException("recordCellUse failed", e);
        }
    }

    @Override
    public boolean isChallengeUsed(String challengeId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_IS_CHALLENGE_USED)) {
            ps.setString(1, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new CotpException("isChallengeUsed failed", e);
        }
    }

    @Override
    public boolean markChallengeUsed(String cardId, String challengeId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT_CHALLENGE)) {
            ps.setString(1, challengeId);
            ps.setString(2, cardId);
            ps.setLong(3, expiresAt.toEpochMilli());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (JdbcUtil.isConstraintViolation(e)) {
                return false;
            }
            throw new CotpException("markChallengeUsed failed", e);
        }
    }

    @Override
    public Set<String> burnedCells(String cardId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_BURNED_CELLS)) {
            ps.setString(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> cells = new HashSet<>();
                while (rs.next()) {
                    cells.add(rs.getString(1));
                }
                return Set.copyOf(cells);
            }
        } catch (SQLException e) {
            throw new CotpException("burnedCells failed", e);
        }
    }

    @Override
    public void reset(String cardId) {
        try (Connection c = dataSource.getConnection()) {
            for (String sql : new String[]{SQL_DELETE_CELL_BURN, SQL_DELETE_CELL_USE, SQL_DELETE_CHALLENGES}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, cardId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new CotpException("reset failed", e);
        }
    }

    @Override
    public void evictExpired(Instant now) {
        // Best-effort: avoid issuing a DELETE on every verification; call purge(now) for an
        // unconditional sweep (e.g. from a scheduled task).
        if (now == null || (evictCounter.incrementAndGet() & (EVICT_GATE - 1)) != 0) {
            return;
        }
        purge(now);
    }

    /**
     * Unconditionally deletes used-challenge rows whose expiry is at or before {@code now}.
     * Safe to call from a scheduled task.
     *
     * @param now current instant
     */
    public void purge(Instant now) {
        if (now == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_EVICT)) {
            ps.setLong(1, now.toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new CotpException("evictExpired failed", e);
        }
    }
}
