package com.cotp;

import java.time.Instant;
import java.util.Objects;

/**
 * A single, short-lived COTP challenge: the verifier asks the user for the word printed at
 * {@link #cell()}.
 *
 * <p>Challenges are stateful objects only in the sense that the verifier tracks their
 * {@code challengeId}, expiry and use. The cell itself is stateless data.</p>
 *
 * @param challengeId unique identifier of this challenge instance
 * @param cardId      card the challenge belongs to
 * @param cell        requested grid cell
 * @param mode        how the cell was selected
 * @param issuedAt    issue timestamp
 * @param expiresAt   expiry timestamp; after this the challenge MUST be rejected
 * @param context     free-form audit context supplied by the application (e.g. {@code "kiosk_login"});
 *                    may be {@code null}
 */
public record CotpChallenge(String challengeId, String cardId, Cell cell,
                            ChallengeMode mode, Instant issuedAt, Instant expiresAt, String context) {

    /** Convenience constructor without an audit context. */
    public CotpChallenge(String challengeId, String cardId, Cell cell,
                         ChallengeMode mode, Instant issuedAt, Instant expiresAt) {
        this(challengeId, cardId, cell, mode, issuedAt, expiresAt, null);
    }

    public CotpChallenge {
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** @return canonical cell id, e.g. {@code "C2"}. */
    public String cellId() {
        return cell.id();
    }

    /**
     * @param now reference instant
     * @return {@code true} if {@code now} is at or after {@link #expiresAt()}
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "CotpChallenge{" + challengeId + ", card=" + cardId + ", cell=" + cell.id()
                + ", mode=" + mode + ", expiresAt=" + expiresAt + "}";
    }
}
