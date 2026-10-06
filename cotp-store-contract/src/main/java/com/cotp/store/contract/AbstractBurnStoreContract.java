package com.cotp.store.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cotp.BurnStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reusable conformance tests for {@link BurnStore} implementations.
 *
 * <p>Subclasses supply a fresh, empty store. Not covered here: {@code evictExpired} timing (its
 * contract is best-effort and may be a no-op for TTL-native stores) — that is verified per
 * implementation.</p>
 */
public abstract class AbstractBurnStoreContract {

    /** @return a fresh, empty store for a single test. */
    protected abstract BurnStore newStore();

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void cellBurnIsOneTimeAndAtomic() {
        BurnStore store = newStore();
        assertFalse(store.isCellBurned("card", "C2"));
        assertTrue(store.markCellBurned("card", "C2"));
        assertTrue(store.isCellBurned("card", "C2"));
        assertFalse(store.markCellBurned("card", "C2"), "second burn must report already-burned");
        assertTrue(store.isCellBurned("card", "C2"));
    }

    @Test
    void burnedCellsSnapshot() {
        BurnStore store = newStore();
        store.markCellBurned("card", "A1");
        store.markCellBurned("card", "B2");
        assertTrue(store.burnedCells("card").contains("A1"));
        assertTrue(store.burnedCells("card").contains("B2"));
        assertEquals(2, store.burnedCells("card").size());
        assertEquals(0, store.burnedCells("other").size());
    }

    @Test
    void challengeIsOneTime() {
        BurnStore store = newStore();
        assertFalse(store.isChallengeUsed("ch1"));
        assertTrue(store.markChallengeUsed("card", "ch1", T0.plusSeconds(120)));
        assertTrue(store.isChallengeUsed("ch1"));
        assertFalse(store.markChallengeUsed("card", "ch1", T0.plusSeconds(120)));
    }

    @Test
    void cooldownTimestampRoundTrip() {
        BurnStore store = newStore();
        assertNull(store.lastCellUse("card", "C2"));
        store.recordCellUse("card", "C2", T0);
        assertEquals(T0, store.lastCellUse("card", "C2"));
        Instant later = T0.plusSeconds(3600);
        store.recordCellUse("card", "C2", later);
        assertEquals(later, store.lastCellUse("card", "C2"));
    }

    @Test
    void resetClearsCellsAndChallenges() {
        BurnStore store = newStore();
        store.markCellBurned("card", "C2");
        store.recordCellUse("card", "C2", T0);
        store.markChallengeUsed("card", "ch1", T0.plusSeconds(120));
        store.reset("card");
        assertFalse(store.isCellBurned("card", "C2"));
        assertNull(store.lastCellUse("card", "C2"));
        assertFalse(store.isChallengeUsed("ch1"));
        assertTrue(store.burnedCells("card").isEmpty());
    }

    @Test
    void resetDoesNotAffectOtherCard() {
        BurnStore store = newStore();
        Instant exp = T0.plusSeconds(120);
        store.markCellBurned("a", "C2");
        store.markCellBurned("b", "C2");
        store.markChallengeUsed("a", "chA", exp);
        store.markChallengeUsed("b", "chB", exp);
        store.reset("a");
        assertFalse(store.isCellBurned("a", "C2"));
        assertFalse(store.isChallengeUsed("chA"));
        assertTrue(store.isCellBurned("b", "C2"));
        assertTrue(store.isChallengeUsed("chB"));
    }
}
