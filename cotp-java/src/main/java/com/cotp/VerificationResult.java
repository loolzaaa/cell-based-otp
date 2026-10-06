package com.cotp;

/**
 * Outcome of a COTP verification attempt.
 */
public enum VerificationResult {

    /** All factors matched; the request is authenticated. */
    OK(true),

    /** The submitted word does not match the card. */
    INVALID_WORD(false),

    /** The PIN is missing or incorrect. */
    INVALID_PIN(false),

    /** The supplied identity anchor does not match the card owner. */
    IDENTITY_MISMATCH(false),

    /** The challenge has expired according to its TTL. */
    CHALLENGE_EXPIRED(false),

    /** The challenge id was already consumed (replay attempt). */
    CHALLENGE_REUSED(false),

    /** The requested cell was already burned and may not be reused. */
    CELL_ALREADY_USED(false),

    /** The card has expired. */
    CARD_EXPIRED(false),

    /** All cells of the card have been burned; re-enrolment is required. */
    CARD_EXHAUSTED(false),

    /** Too many failed attempts; the card is temporarily locked. */
    THROTTLED(false),

    /** The request was malformed (null arguments, wrong card, etc.). */
    INVALID_REQUEST(false);

    private final boolean success;

    VerificationResult(boolean success) {
        this.success = success;
    }

    /** @return {@code true} only for {@link #OK}. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Maps this detailed internal result to a coarse, client-safe {@link ClientError}.
     *
     * <p>All factor-specific failures collapse to {@link ClientError#AUTHENTICATION_FAILED} so the
     * external response does not reveal which factor (word, PIN, identity, replay, ...) failed. The
     * detailed result remains available for audit and metrics.</p>
     *
     * @return the client-safe category
     */
    public ClientError toClientError() {
        return switch (this) {
            case OK -> ClientError.NONE;
            case CHALLENGE_EXPIRED -> ClientError.CHALLENGE_EXPIRED;
            case CARD_EXPIRED -> ClientError.CARD_EXPIRED;
            case THROTTLED -> ClientError.TOO_MANY_ATTEMPTS;
            case INVALID_REQUEST -> ClientError.BAD_REQUEST;
            // INVALID_WORD, INVALID_PIN, IDENTITY_MISMATCH, CHALLENGE_REUSED,
            // CELL_ALREADY_USED, CARD_EXHAUSTED -> generic failure (anti-oracle)
            default -> ClientError.AUTHENTICATION_FAILED;
        };
    }
}
