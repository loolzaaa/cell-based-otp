package com.cotp.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Schema helper for the JDBC-backed COTP stores.
 *
 * <p>The DDL below is written for standard SQL and is verified against H2. Other engines may need
 * minor adjustments (for example, MySQL before 8.0.29 does not support
 * {@code CREATE INDEX IF NOT EXISTS}); the table shapes and the unique constraints are the
 * contract.</p>
 */
public final class JdbcStores {

    private JdbcStores() {
    }

    /** DDL statements creating all tables/indexes (idempotent on engines supporting IF NOT EXISTS). */
    public static final String[] SCHEMA = {
            "CREATE TABLE IF NOT EXISTS cotp_cell_burn ("
                    + " card_id VARCHAR(128) NOT NULL,"
                    + " cell_id VARCHAR(32) NOT NULL,"
                    + " burned_at BIGINT NOT NULL,"
                    + " PRIMARY KEY (card_id, cell_id))",
            "CREATE TABLE IF NOT EXISTS cotp_cell_use ("
                    + " card_id VARCHAR(128) NOT NULL,"
                    + " cell_id VARCHAR(32) NOT NULL,"
                    + " last_used_at BIGINT NOT NULL,"
                    + " PRIMARY KEY (card_id, cell_id))",
            "CREATE TABLE IF NOT EXISTS cotp_challenge ("
                    + " challenge_id VARCHAR(128) NOT NULL,"
                    + " card_id VARCHAR(128) NOT NULL,"
                    + " expires_at BIGINT NOT NULL,"
                    + " PRIMARY KEY (challenge_id))",
            "CREATE INDEX IF NOT EXISTS idx_cotp_challenge_expires ON cotp_challenge (expires_at)",
            "CREATE INDEX IF NOT EXISTS idx_cotp_challenge_card ON cotp_challenge (card_id)",
            "CREATE TABLE IF NOT EXISTS cotp_throttle ("
                    + " card_id VARCHAR(128) NOT NULL,"
                    + " failures INT NOT NULL,"
                    + " locked_until BIGINT,"
                    + " last_access BIGINT,"
                    + " PRIMARY KEY (card_id))",
            "CREATE INDEX IF NOT EXISTS idx_cotp_throttle_access ON cotp_throttle (last_access)"
    };

    /**
     * Creates the COTP schema on the given data source. Safe to call repeatedly.
     *
     * @param dataSource target data source
     * @throws SQLException on DDL failure
     */
    public static void createSchema(DataSource dataSource) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                statement.execute(ddl);
            }
        }
    }
}
