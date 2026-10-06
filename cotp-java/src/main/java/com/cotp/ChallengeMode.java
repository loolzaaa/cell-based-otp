package com.cotp;

/**
 * Strategy used to select the grid cell presented in a challenge.
 *
 * <ul>
 *   <li>{@link #RANDOM} — the verifier picks a uniformly random cell with a CSPRNG.
 *       This mirrors the Avatier Identity Challenge Card behaviour.</li>
 *   <li>{@link #TIME_BASED} — the cell is derived deterministically from the current time
 *       and the card secret via HMAC (a TOTP-style construction). The verifier does not need
 *       to persist challenge state and replay is bounded by the time window; the cell is still
 *       transmitted to the user interface, exactly like the random mode.</li>
 * </ul>
 */
public enum ChallengeMode {
    RANDOM,
    TIME_BASED
}
