package com.cotp;

import java.time.Duration;
import java.time.Instant;

/**
 * Persistence abstraction for online brute-force protection of the private PIN.
 *
 * <p>Counters are keyed by card and MUST be shared across login sessions (RFC 4226 section 7.3):
 * a new session must not reset the failure counter. The verification layer applies the policy
 * (threshold and lockout duration) from {@link CotpPolicy}.</p>
 */
public interface ThrottleStore {

    /** @return current number of consecutive failures for the card. */
    int failureCount(String cardId);

    /** @return the instant until which the card is locked, or {@code null} if not locked. */
    Instant lockedUntil(String cardId);

    /** Sets an explicit lockout deadline. */
    void lockUntil(String cardId, Instant until);

    /**
     * Atomically increments the consecutive-failure counter and returns the new value.
     *
     * <p>Implementations MUST perform the increment atomically (check-and-increment), otherwise
     * concurrent failed attempts can lose increments and weaken lockout protection.</p>
     *
     * @return the failure count after the increment
     */
    int incrementFailure(String cardId);

    /** Clears all throttle state for a card (called after a successful verification). */
    void reset(String cardId);

    /**
     * Best-effort removal of idle throttle state (no active lock and not accessed for
     * {@code idleTtl}). Implementations backed by a native TTL store may treat this as a no-op.
     * Callers may invoke it periodically to bound memory.
     *
     * @param now     current instant
     * @param idleTtl idle threshold
     */
    default void evictIdle(Instant now, Duration idleTtl) {
    }
}
