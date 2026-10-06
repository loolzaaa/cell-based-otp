package com.cotp.redis;

import com.cotp.ThrottleStore;
import com.cotp.store.contract.AbstractThrottleStoreContract;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the shared {@link ThrottleStore} contract against a real Redis instance.
 *
 * <p>Skipped unless {@code COTP_REDIS_URI} is set (for example {@code redis://localhost:6379}).</p>
 */
class RedisThrottleStoreTest extends AbstractThrottleStoreContract {

    private static final String URI = System.getenv("COTP_REDIS_URI");
    private final List<RedisThrottleStore> opened = new ArrayList<>();

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(URI != null && !URI.isBlank(),
                "Set COTP_REDIS_URI (e.g. redis://localhost:6379) to run Redis store tests");
    }

    @AfterEach
    void closeStores() {
        opened.forEach(RedisThrottleStore::close);
        opened.clear();
    }

    @Override
    protected ThrottleStore newStore() {
        RedisThrottleStore store = RedisThrottleStore.create(URI, "cotp:test:" + UUID.randomUUID() + ":");
        opened.add(store);
        return store;
    }
}
