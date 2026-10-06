package com.cotp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Verifies COTP responses.
 *
 * <p>A verification combines up to three factors:</p>
 * <ol>
 *   <li><b>Possession</b> &mdash; the word printed at the challenged cell (the card);</li>
 *   <li><b>Knowledge</b> &mdash; the private PIN (optional, enabled by supplying a PIN hash);</li>
 *   <li><b>Identity anchor</b> &mdash; the directory identity the card belongs to (optional).</li>
 * </ol>
 *
 * <p>The verifier takes the card's {@link CotpScheme} for all derivation, and a {@link CotpPolicy}
 * for burn/cooldown/throttling/PIN behaviour. It enforces expiry, one-time semantics
 * ({@link BurnStore}) and brute-force throttling ({@link ThrottleStore}) across sessions. It never
 * logs or stores the submitted word or PIN.</p>
 *
 * <p>Every verification emits an event to the {@link CotpEventSink} (verification outcome, cell
 * burn/cooldown, lockout). Such events carry only non-secret metadata.</p>
 */
public final class CotpVerifier {

    private final CotpPolicy policy;
    private final PinHasher pinHasher;
    private final BurnStore burnStore;
    private final ThrottleStore throttleStore;
    private final CotpEventSink eventSink;

    /** Creates a verifier with in-memory burn/throttle stores. */
    public CotpVerifier(CotpPolicy policy) {
        this(policy, new InMemoryBurnStore(), new InMemoryThrottleStore(), CotpEventSink.NOOP);
    }

    /**
     * @param policy        policy configuration
     * @param burnStore     one-time state store (may be shared/clustered)
     * @param throttleStore brute-force state store (may be shared/clustered)
     */
    public CotpVerifier(CotpPolicy policy, BurnStore burnStore, ThrottleStore throttleStore) {
        this(policy, burnStore, throttleStore, CotpEventSink.NOOP);
    }

    /**
     * @param policy        policy configuration
     * @param burnStore     one-time state store (may be shared/clustered)
     * @param throttleStore brute-force state store (may be shared/clustered)
     * @param eventSink     sink for audit/observability events (use {@link CotpEventSink#NOOP})
     */
    public CotpVerifier(CotpPolicy policy, BurnStore burnStore, ThrottleStore throttleStore,
                        CotpEventSink eventSink) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.pinHasher = new PinHasher(policy.pinIterations());
        this.burnStore = Objects.requireNonNull(burnStore, "burnStore");
        this.throttleStore = Objects.requireNonNull(throttleStore, "throttleStore");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public CotpPolicy policy() {
        return policy;
    }

    public BurnStore burnStore() {
        return burnStore;
    }

    public ThrottleStore throttleStore() {
        return throttleStore;
    }

    /**
     * Verifies a response without an identity anchor.
     */
    public VerificationResult verify(CotpCard card, String pinHash, CotpChallenge challenge,
                                     String word, char[] pin, Instant now) {
        return verify(card, pinHash, challenge, word, pin, null, null, now);
    }

    /**
     * Verifies a response with an optional identity anchor.
     *
     * @param card               card the challenge belongs to
     * @param pinHash            stored PIN hash, or {@code null} to skip the PIN factor
     * @param challenge          issued challenge (its {@link CotpChallenge#context()} is propagated to events)
     * @param word               user-provided word
     * @param pin                user-provided PIN, or {@code null}
     * @param presentedIdentity  identity presented by the caller, or {@code null} to skip the check
     * @param registeredIdentity identity registered for this card, or {@code null}
     * @param now                current instant
     * @return verification outcome
     */
    public VerificationResult verify(CotpCard card, String pinHash, CotpChallenge challenge,
                                     String word, char[] pin, String presentedIdentity,
                                     String registeredIdentity, Instant now) {
        if (card == null || challenge == null || word == null || now == null) {
            return VerificationResult.INVALID_REQUEST;
        }
        VerificationResult result =
                verifyInternal(card, pinHash, challenge, word, pin, presentedIdentity, registeredIdentity, now);
        emitResult(card, challenge, result, now);
        return result;
    }

    private VerificationResult verifyInternal(CotpCard card, String pinHash, CotpChallenge challenge,
                                              String word, char[] pin, String presentedIdentity,
                                              String registeredIdentity, Instant now) {
        if (!card.cardId().equals(challenge.cardId())) {
            return VerificationResult.INVALID_REQUEST;
        }

        // Derivation always uses the card's own scheme, so a card generated under a different
        // version/layout still verifies correctly instead of silently returning INVALID_WORD.
        CotpScheme scheme = card.scheme();

        // Opportunistic, cheap memory reclamation (gated inside the in-memory stores).
        burnStore.evictExpired(now);
        throttleStore.evictIdle(now, policy.lockDuration());

        if (card.expiresAt() != null && !now.isBefore(card.expiresAt())) {
            return VerificationResult.CARD_EXPIRED;
        }
        if (challenge.isExpired(now)) {
            return VerificationResult.CHALLENGE_EXPIRED;
        }
        if (challenge.mode() == ChallengeMode.TIME_BASED
                && !isValidTimeBasedCell(card, scheme, challenge, now)) {
            return VerificationResult.INVALID_REQUEST;
        }
        if (!card.grid().containsKey(challenge.cellId())) {
            return VerificationResult.INVALID_REQUEST;
        }
        if (isLocked(card.cardId(), now)) {
            return VerificationResult.THROTTLED;
        }
        if (burnStore.isChallengeUsed(challenge.challengeId())) {
            return VerificationResult.CHALLENGE_REUSED;
        }
        if (policy.burnPolicy() == BurnPolicy.CELL) {
            // Exhaustion is checked before the per-cell test so that a fully used card reports
            // CARD_EXHAUSTED rather than CELL_ALREADY_USED.
            if (burnStore.burnedCells(card.cardId()).size() >= scheme.slotCount()) {
                return VerificationResult.CARD_EXHAUSTED;
            }
            if (burnStore.isCellBurned(card.cardId(), challenge.cellId())) {
                return VerificationResult.CELL_ALREADY_USED;
            }
        } else if (policy.burnPolicy() == BurnPolicy.COOLDOWN) {
            Instant lastUsed = burnStore.lastCellUse(card.cardId(), challenge.cellId());
            if (lastUsed != null && lastUsed.plus(policy.cellCooldown()).isAfter(now)) {
                return VerificationResult.CELL_ALREADY_USED;
            }
        }

        // Identity anchor is not a secret factor: a mismatch MUST NOT feed the PIN throttle
        // (otherwise anyone who knows the employee id could lock the card). Blank/absent anchors are
        // treated as "not enforced"; if both sides are provided they must match exactly.
        String presented = normalizeIdentity(presentedIdentity);
        String registered = normalizeIdentity(registeredIdentity);
        if (presented != null && registered != null && !presented.equals(registered)) {
            return VerificationResult.IDENTITY_MISMATCH;
        }

        String expected = new CotpCardGenerator(scheme).wordFor(card.cardId(), card.secret(), challenge.cell());
        if (!ConstantTime.wordsMatch(word, expected)) {
            return failure(card.cardId(), challenge.context(), VerificationResult.INVALID_WORD, now);
        }

        if (pinHash != null) {
            if (pin == null || !pinHasher.verify(pin, pinHash)) {
                return failure(card.cardId(), challenge.context(), VerificationResult.INVALID_PIN, now);
            }
        }

        // Success: consume the challenge and (optionally) record the cell atomically. The atomic
        // check-and-set return value MUST be honoured, otherwise two concurrent verifications of the
        // same challenge could both succeed (notably under CHALLENGE policy, where there is no cell
        // burn to catch the race).
        if (!burnStore.markChallengeUsed(card.cardId(), challenge.challengeId(), challenge.expiresAt())) {
            return VerificationResult.CHALLENGE_REUSED;
        }
        if (policy.burnPolicy() == BurnPolicy.CELL) {
            if (!burnStore.markCellBurned(card.cardId(), challenge.cellId())) {
                return VerificationResult.CELL_ALREADY_USED;
            }
        } else if (policy.burnPolicy() == BurnPolicy.COOLDOWN) {
            burnStore.recordCellUse(card.cardId(), challenge.cellId(), now);
        }
        throttleStore.reset(card.cardId());
        return VerificationResult.OK;
    }

    private void emitResult(CotpCard card, CotpChallenge challenge, VerificationResult result, Instant now) {
        CotpEventType type = switch (result) {
            case OK -> CotpEventType.VERIFY_SUCCEEDED;
            case CHALLENGE_REUSED -> CotpEventType.CHALLENGE_REUSED_ATTEMPT;
            case CARD_EXHAUSTED -> CotpEventType.CARD_EXHAUSTED;
            default -> CotpEventType.VERIFY_FAILED;
        };
        eventSink.onEvent(new CotpEvent(type, now, card.cardId(), challenge.context(),
                challenge.mode(), challenge.cellId(), result));
        if (result == VerificationResult.OK) {
            if (policy.burnPolicy() == BurnPolicy.CELL) {
                eventSink.onEvent(new CotpEvent(CotpEventType.CELL_BURNED, now, card.cardId(),
                        challenge.context(), challenge.mode(), challenge.cellId(), result));
            } else if (policy.burnPolicy() == BurnPolicy.COOLDOWN) {
                eventSink.onEvent(new CotpEvent(CotpEventType.CELL_COOLDOWN_RECORDED, now, card.cardId(),
                        challenge.context(), challenge.mode(), challenge.cellId(), result));
            }
        }
    }

    private boolean isLocked(String cardId, Instant now) {
        Instant until = throttleStore.lockedUntil(cardId);
        if (until == null) {
            return false;
        }
        if (now.isBefore(until)) {
            return true;
        }
        // Lock elapsed: clear throttle state and allow a fresh set of attempts.
        throttleStore.reset(cardId);
        return false;
    }

    private VerificationResult failure(String cardId, String context, VerificationResult result, Instant now) {
        int failures = throttleStore.incrementFailure(cardId);
        if (failures >= policy.maxFailures()) {
            throttleStore.lockUntil(cardId, now.plus(policy.lockDuration()));
            eventSink.onEvent(new CotpEvent(CotpEventType.THROTTLE_LOCKOUT, now, cardId, context,
                    null, null, result));
        }
        return result;
    }

    /**
     * Validates a {@link ChallengeMode#TIME_BASED} challenge: its cell MUST match the deterministic
     * time-derived selection for one of the steps within the scheme's drift window around the
     * verification time {@code now}. This makes the window a real control: it bounds how far (in time
     * steps) a valid challenge may be from the verifier's clock, and rejects cells that do not
     * correspond to any legitimate time-derived selection.
     */
    private boolean isValidTimeBasedCell(CotpCard card, CotpScheme scheme,
                                         CotpChallenge challenge, Instant now) {
        byte[] challengeKey = CotpDerivation.challengeKey(
                card.secret().copyBytes(), card.cardId(), scheme);
        List<Cell> cells = scheme.cells();
        long nowStep = Math.floorDiv(now.getEpochSecond(), scheme.timeStepSeconds());
        for (int d = -scheme.timeWindowBack(); d <= scheme.timeWindowForward(); d++) {
            int idx = CotpDerivation.selectSlotIndex(challengeKey, nowStep + d, cells.size(), scheme);
            if (cells.get(idx).id().equals(challenge.cellId())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIdentity(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
