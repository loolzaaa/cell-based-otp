package ru.loolzaaa;

/**
 * Defines what is permanently invalidated after a successful verification.
 *
 * <ul>
 *   <li>{@link #CELL} — the answered cell is burned and the verifier MUST NOT issue it again.
 *       This is the strict, Avatier-like "one-time challenge value" behaviour. A card with
 *       {@code N} slots therefore supports exactly {@code N} successful verifications; after
 *       that it is exhausted and must be re-enrolled.</li>
 *   <li>{@link #COOLDOWN} — a cell may be reused, but not before
 *       {@link CotpPolicy#cellCooldown()} has elapsed since its last successful use. A middle
 *       ground between {@link #CELL} (too small a lifetime for frequent logins) and
 *       {@link #CHALLENGE} (a cell may reappear immediately). The cooldown limits how often the
 *       same cell is shown and how quickly an observer can accumulate distinct cell answers.</li>
 *   <li>{@link #CHALLENGE} — only the challenge instance is one-time (its id cannot be replayed);
 *       the same cell may be used again immediately. Appropriate for high-frequency step-up flows
 *       where any per-cell restriction is undesirable. Should be combined with a short time window
 *       and PIN throttling.</li>
 *   <li>{@link #NONE} — no burn (testing / fully stateless time-based mode only). Not recommended
 *       for production.</li>
 * </ul>
 */
public enum BurnPolicy {
    CELL,
    COOLDOWN,
    CHALLENGE,
    NONE
}
