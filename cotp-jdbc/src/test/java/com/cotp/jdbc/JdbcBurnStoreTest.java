package com.cotp.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cotp.BurnStore;
import com.cotp.store.contract.AbstractBurnStoreContract;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcBurnStoreTest extends AbstractBurnStoreContract {

    @Override
    protected BurnStore newStore() {
        try {
            return new JdbcBurnStore(H2Support.freshDatabase());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void evictExpiredRemovesExpiredChallenges() {
        try {
            JdbcBurnStore store = new JdbcBurnStore(H2Support.freshDatabase());
            Instant now = Instant.parse("2026-10-06T12:00:00Z");
            store.markChallengeUsed("card", "old", now.minusSeconds(1));
            store.markChallengeUsed("card", "future", now.plusSeconds(600));
            store.purge(now);
            assertFalse(store.isChallengeUsed("old"));
            assertTrue(store.isChallengeUsed("future"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
