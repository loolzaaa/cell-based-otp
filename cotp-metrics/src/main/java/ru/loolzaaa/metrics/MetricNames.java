package ru.loolzaaa.metrics;

/**
 * Canonical metric names and tag keys emitted by {@code cotp-metrics}.
 *
 * <p>All metric names use the {@code cotp_} prefix and {@code snake_case}, as defined by the
 * observability task ({@code TODO.md}, {@code [P1-B]}). Tag keys are intentionally restricted to
 * categorical values ({@link MetricsTags#TAG_RESULT}, {@link MetricsTags#TAG_CONTEXT},
 * {@link MetricsTags#TAG_MODE}, {@link MetricsTags#TAG_LAYOUT}, {@link MetricsTags#TAG_REASON},
 * {@link MetricsTags#TAG_OPERATION}); identifiers and secrets must never be used as tags.</p>
 */
public final class MetricNames {

    /** Common metric name prefix. */
    public static final String PREFIX = "cotp";

    /** Counter: challenges issued, tags {@code mode}, {@code context}. */
    public static final String CHALLENGE_ISSUED_TOTAL = "cotp_challenge_issued_total";
    /** Counter: verification outcomes, tags {@code result}, {@code context}. */
    public static final String VERIFICATION_TOTAL = "cotp_verification_total";
    /** Histogram/timer: verification latency in seconds, tags {@code context}, {@code result}. */
    public static final String VERIFICATION_DURATION_SECONDS = "cotp_verification_duration_seconds";
    /** Histogram/timer: grid generation latency in seconds, tag {@code layout}. */
    public static final String GRID_GENERATION_DURATION_SECONDS = "cotp_grid_generation_duration_seconds";
    /** Counter: permanently burned cells, tag {@code layout}. */
    public static final String CELL_BURNED_TOTAL = "cotp_cell_burned_total";
    /** Counter: cards whose cells are exhausted, tag {@code layout}. */
    public static final String CARDS_EXHAUSTED_TOTAL = "cotp_cards_exhausted_total";
    /** Counter: expired challenges, tag {@code context}. */
    public static final String CHALLENGE_EXPIRED_TOTAL = "cotp_challenge_expired_total";
    /** Counter: replay attempts against a consumed challenge (no tags). */
    public static final String CHALLENGE_REUSE_TOTAL = "cotp_challenge_reuse_total";
    /** Counter: wrong PIN, tag {@code context}. */
    public static final String PIN_VERIFY_FAILURES_TOTAL = "cotp_pin_verify_failures_total";
    /** Counter: throttle lockouts, tag {@code context}. */
    public static final String THROTTLE_LOCKOUTS_TOTAL = "cotp_throttle_lockouts_total";
    /** Counter: identity-anchor mismatches, tag {@code context}. */
    public static final String IDENTITY_MISMATCH_TOTAL = "cotp_identity_mismatch_total";
    /** Counter: cards enrolled/issued, tag {@code layout}. */
    public static final String ENROLLMENT_TOTAL = "cotp_enrollment_total";
    /** Counter: card reissues, tag {@code reason}. */
    public static final String REISSUE_TOTAL = "cotp_reissue_total";
    /** Counter: card revocations, tag {@code reason}. */
    public static final String REVOCATION_TOTAL = "cotp_revocation_total";
    /** Counter: secret decryption/KMS failures (no tags). */
    public static final String SECRET_DECRYPT_FAILURES_TOTAL = "cotp_secret_decrypt_failures_total";
    /** Gauge: active cards, tag {@code layout}. */
    public static final String ACTIVE_CARDS = "cotp_active_cards";
    /** Histogram/summary: collision nonce distribution, tag {@code layout}. */
    public static final String COLLISION_NONCE = "cotp_collision_nonce";
    /** Gauge: configured challenge TTL in seconds (no tags). */
    public static final String CHALLENGE_TTL_SECONDS = "cotp_challenge_ttl_seconds";
    /** Counter: general operation traffic, tag {@code operation}. */
    public static final String OPERATIONS_TOTAL = "cotp_operations_total";

    private MetricNames() {
    }
}
