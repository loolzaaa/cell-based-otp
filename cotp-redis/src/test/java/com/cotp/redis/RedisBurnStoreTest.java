package com.cotp.redis;

import com.cotp.BurnStore;
import com.cotp.store.contract.AbstractBurnStoreContract;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the shared {@link BurnStore} contract against a real Redis instance.
 *
 * <p>Skipped unless the {@code COTP_REDIS_URI} environment variable is set (for example
 * {@code redis://localhost:6379}). Each store uses a unique key prefix for isolation.</p>
 */
class RedisBurnStoreTest extends AbstractBurnStoreContract {

    private static final String URI = System.getenv("COTP_REDIS_URI");
    private final List<RedisBurnStore> opened = new ArrayList<>();

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(URI != null && !URI.isBlank(),
                "Set COTP_REDIS_URI (e.g. redis://localhost:6379) to run Redis store tests");
    }

    @AfterEach
    void closeStores() {
        opened.forEach(RedisBurnStore::close);
        opened.clear();
    }

    @Override
    protected BurnStore newStore() {
        RedisBurnStore store = RedisBurnStore.create(URI, "cotp:test:" + UUID.randomUUID() + ":");
        opened.add(store);
        return store;
    }

    @org.junit.jupiter.api.Test
    void challengeIndexIsTrimmed() throws Exception {
        String prefix = "cotp:test:" + UUID.randomUUID() + ":";
        io.lettuce.core.RedisClient client = io.lettuce.core.RedisClient.create(URI);
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect()) {
            io.lettuce.core.api.sync.RedisCommands<String, String> cmds = conn.sync();
            RedisBurnStore store = new RedisBurnStore(cmds, prefix);
            java.time.Instant now = java.time.Instant.now();
            store.markChallengeUsed("C", "ch1", now.plusMillis(100));
            Thread.sleep(250); // let ch1 expire
            store.markChallengeUsed("C", "ch2", now.plusSeconds(3600)); // triggers trim of ch1
            org.junit.jupiter.api.Assertions.assertEquals(1L, cmds.zcard(prefix + "ch:C").longValue());
        } finally {
            client.shutdown();
        }
    }

    @org.junit.jupiter.api.Test
    void indexTtlIsNotShrunkByShorterChallenge() throws Exception {
        String prefix = "cotp:test:" + UUID.randomUUID() + ":";
        io.lettuce.core.RedisClient client = io.lettuce.core.RedisClient.create(URI);
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect()) {
            io.lettuce.core.api.sync.RedisCommands<String, String> cmds = conn.sync();
            RedisBurnStore store = new RedisBurnStore(cmds, prefix);
            java.time.Instant now = java.time.Instant.now();
            store.markChallengeUsed("C", "long", now.plusSeconds(3600));
            store.markChallengeUsed("C", "short", now.plusMillis(300));
            Thread.sleep(450); // the short challenge expires
            // the long challenge is still live and must remain discoverable through the index
            org.junit.jupiter.api.Assertions.assertTrue(store.isChallengeUsed("long"));
            store.reset("C");
            org.junit.jupiter.api.Assertions.assertFalse(store.isChallengeUsed("long"));
        } finally {
            client.shutdown();
        }
    }

    @org.junit.jupiter.api.Test
    void identifiersContainingColonDoNotCollide() {
        BurnStore store = newStore();
        // card "A" cell "B" must not share keys with card "A:B" (the separator is escaped).
        org.junit.jupiter.api.Assertions.assertTrue(store.markCellBurned("A", "B"));
        org.junit.jupiter.api.Assertions.assertTrue(store.markCellBurned("A:B", "Z"),
                "no WRONGTYPE collision for ':' in identifiers");
        org.junit.jupiter.api.Assertions.assertTrue(store.burnedCells("A").contains("B"));
        org.junit.jupiter.api.Assertions.assertFalse(store.burnedCells("A").contains("Z"));
        org.junit.jupiter.api.Assertions.assertTrue(store.burnedCells("A:B").contains("Z"));
        org.junit.jupiter.api.Assertions.assertFalse(store.burnedCells("A:B").contains("B"));
    }
}
