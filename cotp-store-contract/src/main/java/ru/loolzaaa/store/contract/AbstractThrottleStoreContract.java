package ru.loolzaaa.store.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ru.loolzaaa.ThrottleStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reusable conformance tests for {@link ThrottleStore} implementations.
 *
 * <p>Subclasses supply a fresh, empty store. {@code evictIdle} timing is implementation-specific and
 * not covered here.</p>
 */
public abstract class AbstractThrottleStoreContract {

    /** @return a fresh, empty store for a single test. */
    protected abstract ThrottleStore newStore();

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void incrementFailureIncreasesAtomically() {
        ThrottleStore store = newStore();
        assertEquals(0, store.failureCount("card"));
        assertEquals(1, store.incrementFailure("card"));
        assertEquals(2, store.incrementFailure("card"));
        assertEquals(2, store.failureCount("card"));
    }

    @Test
    void lockRoundTrip() {
        ThrottleStore store = newStore();
        assertNull(store.lockedUntil("card"));
        Instant until = T0.plusSeconds(900);
        store.lockUntil("card", until);
        assertEquals(until, store.lockedUntil("card"));
    }

    @Test
    void resetClearsState() {
        ThrottleStore store = newStore();
        store.incrementFailure("card");
        store.lockUntil("card", T0.plusSeconds(900));
        store.reset("card");
        assertEquals(0, store.failureCount("card"));
        assertNull(store.lockedUntil("card"));
    }
}
