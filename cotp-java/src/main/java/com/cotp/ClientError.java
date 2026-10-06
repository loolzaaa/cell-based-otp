package com.cotp;

/**
 * Client-safe, coarse-grained outcome of a verification, suitable for exposing to end users.
 *
 * <p>Internal {@link VerificationResult}s are deliberately detailed (they distinguish which factor
 * failed) and must NOT be shown to clients, since that would be an oracle. {@link #AUTHENTICATION_FAILED}
 * collapses all factor-specific failures into a single response.</p>
 */
public enum ClientError {

    /** Verification succeeded. */
    NONE(true, "OK"),

    /** Generic authentication failure (wrong word, wrong PIN, identity mismatch, replay, ...). */
    AUTHENTICATION_FAILED(false, "Authentication failed"),

    /** The challenge has expired; request a new one. */
    CHALLENGE_EXPIRED(false, "Challenge expired"),

    /** The card has expired; re-enrolment is required. */
    CARD_EXPIRED(false, "Card expired"),

    /** Too many attempts; the card is temporarily locked. */
    TOO_MANY_ATTEMPTS(false, "Too many attempts, try again later"),

    /** Malformed request (missing/unknown card or challenge). */
    BAD_REQUEST(false, "Bad request");

    private final boolean success;
    private final String genericMessage;

    ClientError(boolean success, String genericMessage) {
        this.success = success;
        this.genericMessage = genericMessage;
    }

    /** @return {@code true} only for {@link #NONE}. */
    public boolean isSuccess() {
        return success;
    }

    /** @return a generic, non-leaking message safe to show to clients. */
    public String genericMessage() {
        return genericMessage;
    }
}
