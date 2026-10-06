package com.cotp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cotp.ThrottleStore;
import com.cotp.store.contract.AbstractThrottleStoreContract;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class JdbcThrottleStoreTest extends AbstractThrottleStoreContract {

    @Override
    protected ThrottleStore newStore() {
        try {
            return new JdbcThrottleStore(H2Support.freshDatabase());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        JdbcThrottleStore store = new JdbcThrottleStore(H2Support.freshDatabase());
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < perThread; j++) {
                    store.incrementFailure("card");
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(threads * perThread, store.failureCount("card"));
    }
}
