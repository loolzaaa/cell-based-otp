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
}
