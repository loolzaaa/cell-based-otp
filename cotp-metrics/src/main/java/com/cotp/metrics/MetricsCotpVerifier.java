package com.cotp.metrics;

import com.cotp.BurnStore;
import com.cotp.CotpCard;
import com.cotp.CotpChallenge;
import com.cotp.CotpPolicy;
import com.cotp.CotpVerifier;
import com.cotp.ThrottleStore;
import com.cotp.VerificationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Delegating decorator around {@link CotpVerifier} that records
 * {@code cotp_verification_duration_seconds} and increments {@code cotp_operations_total}.
 *
 * <p>The verification outcome counters themselves are produced from the core's audit events (via
 * {@code MetricsEventSink}); this decorator only adds the latency dimension that a single event
 * cannot carry. The delegate continues to own all policy and state.</p>
 */
public final class MetricsCotpVerifier {

    private final CotpVerifier delegate;
    private final CotpMetrics metrics;

    public MetricsCotpVerifier(CotpVerifier delegate, CotpMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** @return the wrapped verifier. */
    public CotpVerifier delegate() {
        return delegate;
    }

    public CotpPolicy policy() {
        return delegate.policy();
    }

    public BurnStore burnStore() {
        return delegate.burnStore();
    }

    public ThrottleStore throttleStore() {
        return delegate.throttleStore();
    }

    /** Delegates to {@link CotpVerifier#verify(CotpCard, String, CotpChallenge, String, char[], Instant)}. */
    public VerificationResult verify(CotpCard card, String pinHash, CotpChallenge challenge,
                                     String word, char[] pin, Instant now) {
        return timed(challenge, () -> delegate.verify(card, pinHash, challenge, word, pin, now));
    }

    /** Delegates to the identity-aware {@link CotpVerifier#verify} overload. */
    public VerificationResult verify(CotpCard card, String pinHash, CotpChallenge challenge,
                                     String word, char[] pin, String presentedIdentity,
                                     String registeredIdentity, Instant now) {
        return timed(challenge, () -> delegate.verify(card, pinHash, challenge, word, pin,
                presentedIdentity, registeredIdentity, now));
    }

    private VerificationResult timed(CotpChallenge challenge, VerificationCall call) {
        long start = System.nanoTime();
        VerificationResult result = call.verify();
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        String context = challenge == null ? null : challenge.context();
        metrics.recordVerificationDuration(context, result, duration);
        metrics.recordOperation("verify");
        return result;
    }

    @FunctionalInterface
    private interface VerificationCall {
        VerificationResult verify();
    }
}
