package com.cotp.jdbc;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Creates throwaway in-memory H2 data sources (one per call) with the COTP schema applied.
 */
final class H2Support {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private H2Support() {
    }

    static JdbcDataSource freshDatabase() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cotp_" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcStores.createSchema(ds);
        return ds;
    }
}
