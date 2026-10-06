package com.cotp;

/**
 * Kind of a COTP audit/observability event emitted by the core (see {@link CotpEventSink}).
 */
public enum CotpEventType {

    /** A challenge was issued to a user. */
    CHALLENGE_ISSUED,

    /** A verification succeeded (all applicable factors matched). */
    VERIFY_SUCCEEDED,

    /** A verification failed (see {@link CotpEvent#result()}). */
    VERIFY_FAILED,

    /** A verification was rejected because the challenge id had already been consumed. */
    CHALLENGE_REUSED_ATTEMPT,

    /** A verification was rejected because the card has no available cells left. */
    CARD_EXHAUSTED,

    /** A cell was permanently burned after a successful verification ({@link BurnPolicy#CELL}). */
    CELL_BURNED,

    /** A cell usage was recorded for the cooldown window ({@link BurnPolicy#COOLDOWN}). */
    CELL_COOLDOWN_RECORDED,

    /** The card was temporarily locked after too many consecutive failures. */
    THROTTLE_LOCKOUT
}
