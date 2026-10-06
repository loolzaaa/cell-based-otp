package ru.loolzaaa.metrics;

import ru.loolzaaa.ChallengeMode;
import ru.loolzaaa.VerificationResult;

/**
 * Helpers for building the categorical tags used by {@code cotp-metrics}.
 *
 * <p><b>[НОРМА]</b> Only categorical, low-cardinality tags are allowed. Identifiers such as
 * {@code card_id}/{@code employee_id}, {@code challenge_id}, and secrets such as the printed
 * {@code word} or {@code pin} MUST never become tags: they are PII/secrets and would explode the
 * Prometheus time series. Per-card anomalies belong in the audit/SIEM stream, not in metrics.</p>
 *
 * <p>Null or blank tag values are normalized to a stable sentinel ({@link #NONE} or
 * {@link #UNKNOWN}) so that a label never has an empty value.</p>
 */
public final class MetricsTags {

    /** Tag key for the verification outcome ({@link VerificationResult#name()}). */
    public static final String TAG_RESULT = "result";
    /** Tag key for the application audit context (e.g. {@code kiosk_login}). */
    public static final String TAG_CONTEXT = "context";
    /** Tag key for the challenge mode ({@link ChallengeMode#name()}). */
    public static final String TAG_MODE = "mode";
    /** Tag key for the card layout (application-configured, e.g. {@code 5x5}). */
    public static final String TAG_LAYOUT = "layout";
    /** Tag key for a reissue/revocation reason. */
    public static final String TAG_REASON = "reason";
    /** Tag key for a general operation name. */
    public static final String TAG_OPERATION = "operation";

    /** Sentinel used when an optional context is absent. */
    public static final String NONE = "none";
    /** Sentinel used when a categorical value is absent. */
    public static final String UNKNOWN = "unknown";

    private MetricsTags() {
    }

    /** @return normalized context value, {@link #NONE} when null/blank. */
    public static String context(String raw) {
        return normalize(raw, NONE);
    }

    /** @return normalized layout value, {@link #UNKNOWN} when null/blank. */
    public static String layout(String raw) {
        return normalize(raw, UNKNOWN);
    }

    /** @return normalized reason value, {@link #UNKNOWN} when null/blank. */
    public static String reason(String raw) {
        return normalize(raw, UNKNOWN);
    }

    /** @return normalized operation value, {@link #UNKNOWN} when null/blank. */
    public static String operation(String raw) {
        return normalize(raw, UNKNOWN);
    }

    /** @return the mode name, or {@link #UNKNOWN} when null. */
    public static String mode(ChallengeMode mode) {
        return mode == null ? UNKNOWN : mode.name();
    }

    /** @return the result name, or {@link #UNKNOWN} when null. */
    public static String result(VerificationResult result) {
        return result == null ? UNKNOWN : result.name();
    }

    /**
     * Trims a raw tag value and substitutes {@code fallback} when it is null or blank.
     *
     * @param raw      raw value (may be {@code null})
     * @param fallback sentinel to use when {@code raw} is null/blank
     * @return a non-blank tag value
     */
    public static String normalize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
