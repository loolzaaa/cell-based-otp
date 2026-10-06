package ru.loolzaaa;

import java.time.Instant;
import java.util.Set;

/**
 * Persistence abstraction for COTP one-time semantics.
 *
 * <p>State tracked:</p>
 * <ul>
 *   <li><b>Burned cells</b> — used by {@link BurnPolicy#CELL}. Once a cell has been answered
 *       successfully it must never be issued again for that card.</li>
 *   <li><b>Cell usage timestamps</b> — used by {@link BurnPolicy#COOLDOWN} to allow reuse only after
 *       {@link CotpPolicy#cellCooldown()} has elapsed.</li>
 *   <li><b>Used challenge ids</b> — prevents replay of a specific challenge instance in all burn
 *       policies.</li>
 * </ul>
 *
 * <p>Implementations MUST guarantee that {@link #markCellBurned(String, String)} and
 * {@link #markChallengeUsed(String, String, Instant)} are atomic check-and-set, so that two
 * concurrent verifications of the same cell/challenge cannot both succeed. {@link #recordCellUse}
 * is a plain (last-write) timestamp used for the best-effort {@link BurnPolicy#COOLDOWN} window and
 * is NOT a hard one-time guarantee; under COOLDOWN the one-time property is still provided by the
 * atomic challenge mark. The bundled {@link InMemoryBurnStore} is intended for single-node
 * deployments and tests; production deployments should provide a distributed implementation (for
 * example over Redis or the application database) with the same contract — using native TTL for the
 * challenge entries.</p>
 */
public interface BurnStore {

    /** @return {@code true} if {@code cellId} of {@code cardId} has already been burned. */
    boolean isCellBurned(String cardId, String cellId);

    /**
     * Atomically marks a cell as permanently burned ({@link BurnPolicy#CELL}).
     *
     * @return {@code true} if the cell was newly marked, {@code false} if it was already burned
     */
    boolean markCellBurned(String cardId, String cellId);

    /**
     * @return the instant the cell was last used (for {@link BurnPolicy#COOLDOWN}), or {@code null}
     *         if the cell has never been used.
     */
    Instant lastCellUse(String cardId, String cellId);

    /**
     * Records a timestamped cell use (for {@link BurnPolicy#COOLDOWN}).
     */
    void recordCellUse(String cardId, String cellId, Instant at);

    /** @return {@code true} if the challenge id was already consumed. */
    boolean isChallengeUsed(String challengeId);

    /**
     * Atomically marks a challenge id as used, associates it with its card (so that
     * {@link #reset(String)} can clear it on re-enrolment/revocation), and records the instant
     * after which the entry may be evicted.
     *
     * @param expiresAt the challenge expiry; used to reclaim the entry
     * @return {@code true} if newly marked, {@code false} if already used
     */
    boolean markChallengeUsed(String cardId, String challengeId, Instant expiresAt);

    /** @return an immutable snapshot of all burned cell ids of a card. */
    Set<String> burnedCells(String cardId);

    /** Clears all state for a card (for example on re-enrolment or in tests). */
    void reset(String cardId);

    /**
     * Best-effort removal of expired entries (used-challenge ids whose expiry has passed, ...).
     * Implementations backed by a native TTL store may treat this as a no-op. Callers may invoke it
     * periodically.
     */
    default void evictExpired(Instant now) {
    }
}
