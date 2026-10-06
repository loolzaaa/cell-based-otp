package com.cotp;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Issues COTP challenges. A challenge names the cell whose printed word the user must read back.
 *
 * <p>The <b>scheme</b> (grid layout, time step, version) is always taken from the
 * {@link CotpCard#scheme() card being challenged}, so a challenge can never be generated against a
 * layout/version different from the card. Operational <b>policy</b> (burn policy, TTL, challenge
 * mode, cooldown) comes from this generator's {@link CotpPolicy}.</p>
 *
 * <p>Every issued challenge emits a {@link CotpEventType#CHALLENGE_ISSUED} event to the configured
 * {@link CotpEventSink}. An optional free-form {@code context} (e.g. {@code "kiosk_login"}) is
 * stamped on the challenge and carried into verification events.</p>
 */
public final class CotpChallengeGenerator {

    private final CotpPolicy policy;
    private final CotpEventSink eventSink;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param policy policy configuration (burn policy, TTL, challenge mode, cooldown)
     */
    public CotpChallengeGenerator(CotpPolicy policy) {
        this(policy, CotpEventSink.NOOP);
    }

    /**
     * @param policy    policy configuration
     * @param eventSink sink for audit/observability events (use {@link CotpEventSink#NOOP} to disable)
     */
    public CotpChallengeGenerator(CotpPolicy policy, CotpEventSink eventSink) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /** @return the policy used by this generator. */
    public CotpPolicy policy() {
        return policy;
    }

    /**
     * Issues a new challenge according to {@link CotpPolicy#challengeMode()}.
     */
    public CotpChallenge newChallenge(CotpCard card, Instant now, BurnStore burnStore) {
        return newChallenge(card, now, burnStore, null);
    }

    /**
     * Issues a new challenge with an audit context.
     *
     * @param context free-form context carried into verification events (may be {@code null})
     */
    public CotpChallenge newChallenge(CotpCard card, Instant now, BurnStore burnStore, String context) {
        if (policy.challengeMode() == ChallengeMode.TIME_BASED) {
            return timeBased(card, now, 0, context);
        }
        return random(card, now, burnStore, context);
    }

    /**
     * Issues a challenge with a uniformly random cell. Under {@link BurnPolicy#CELL},
     * already-burned cells are excluded; under {@link BurnPolicy#COOLDOWN}, cells still within the
     * cooldown window are excluded.
     *
     * @throws CardExhaustedException if no cell is available
     */
    public CotpChallenge random(CotpCard card, Instant now, BurnStore burnStore) {
        return random(card, now, burnStore, null);
    }

    /**
     * Issues a random challenge with an audit context.
     *
     * @throws CardExhaustedException if no cell is available
     */
    public CotpChallenge random(CotpCard card, Instant now, BurnStore burnStore, String context) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(now, "now");
        List<Cell> available = new ArrayList<>(card.scheme().cells());
        if (burnStore != null) {
            if (policy.burnPolicy() == BurnPolicy.CELL) {
                available.removeIf(c -> burnStore.isCellBurned(card.cardId(), c.id()));
            } else if (policy.burnPolicy() == BurnPolicy.COOLDOWN) {
                Instant cutoff = now.minus(policy.cellCooldown());
                available.removeIf(c -> {
                    Instant lastUsed = burnStore.lastCellUse(card.cardId(), c.id());
                    return lastUsed != null && lastUsed.isAfter(cutoff);
                });
            }
        }
        if (available.isEmpty()) {
            throw new CardExhaustedException(card.cardId(),
                    policy.burnPolicy() == BurnPolicy.COOLDOWN
                            ? "all cells are within the cooldown window"
                            : "all cells are burned");
        }
        Cell cell = available.get(random.nextInt(available.size()));
        return newChallenge(card.cardId(), cell, ChallengeMode.RANDOM, now, policy.challengeTtl(), context);
    }

    /**
     * Issues a challenge whose cell is deterministically derived from the current time step.
     * No server-side challenge state is required to recompute the cell.
     */
    public CotpChallenge timeBased(CotpCard card, Instant now) {
        return timeBased(card, now, 0, null);
    }

    /** Issues a time-based challenge with an audit context. */
    public CotpChallenge timeBased(CotpCard card, Instant now, String context) {
        return timeBased(card, now, 0, context);
    }

    /**
     * Issues a time-based challenge for {@code now} shifted by {@code stepOffset} time steps.
     *
     * <p>The challenge is stamped with {@code issuedAt = now}; the cell is derived from
     * {@code floor(now / step) + stepOffset}. Verification accepts the cell only if it matches a
     * step within the scheme's drift window around the verification time, therefore
     * {@code stepOffset} must lie inside that window.</p>
     *
     * @param stepOffset number of steps to add (may be negative); used to tolerate clock drift
     */
    public CotpChallenge timeBased(CotpCard card, Instant now, int stepOffset) {
        return timeBased(card, now, stepOffset, null);
    }

    private CotpChallenge timeBased(CotpCard card, Instant now, int stepOffset, String context) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(now, "now");
        CotpScheme scheme = card.scheme();
        long stepSeconds = scheme.timeStepSeconds();
        long timeStep = Math.floorDiv(now.getEpochSecond(), stepSeconds) + stepOffset;
        byte[] challengeKey = CotpDerivation.challengeKey(card.secret().copyBytes(), card.cardId(), scheme);
        List<Cell> cells = scheme.cells();
        int index = CotpDerivation.selectSlotIndex(challengeKey, timeStep, cells.size(), scheme);
        // The challenge is verifiable while the verifier's current step stays within
        // [issueStep - timeWindowForward, issueStep + timeWindowBack]; i.e. until the exclusive
        // boundary (issueStep + timeWindowBack + 1) * step. Bound the TTL by that remaining time so
        // a challenge never becomes "unexpired but unverifiable", and a scheme with
        // timeWindowBack=0 still yields a positive, usable TTL for the current step.
        long endBoundarySeconds = (timeStep + scheme.timeWindowBack() + 1L) * stepSeconds;
        long remainingMillis = Math.max(1L,
                Duration.between(now, Instant.ofEpochSecond(endBoundarySeconds)).toMillis());
        Duration ttl = Duration.ofMillis(Math.min(policy.challengeTtl().toMillis(), remainingMillis));
        return newChallenge(card.cardId(), cells.get(index), ChallengeMode.TIME_BASED, now, ttl, context);
    }

    private CotpChallenge newChallenge(String cardId, Cell cell, ChallengeMode mode, Instant now,
                                       Duration ttl, String context) {
        String id = newChallengeId();
        CotpChallenge challenge = new CotpChallenge(id, cardId, cell, mode, now, now.plus(ttl), context);
        eventSink.onEvent(new CotpEvent(CotpEventType.CHALLENGE_ISSUED, now, cardId, context, mode, cell.id(), null));
        return challenge;
    }

    /** @return a fresh, unpredictable challenge identifier (128-bit, Base32). */
    public String newChallengeId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base32.encode(b);
    }
}
