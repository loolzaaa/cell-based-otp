package com.cotp.metrics;

import com.cotp.BurnStore;
import com.cotp.CotpCard;
import com.cotp.CotpChallenge;
import com.cotp.CotpChallengeGenerator;
import com.cotp.CotpPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Delegating decorator around {@link CotpChallengeGenerator} that increments
 * {@code cotp_operations_total{operation="challenge"}}.
 *
 * <p>The per-issue counters ({@code cotp_challenge_issued_total{...}}) are produced by the core
 * audit events ({@link MetricsEventSink}); this decorator only adds a coarse operation tally.</p>
 */
public final class MetricsCotpChallengeGenerator {

    private final CotpChallengeGenerator delegate;
    private final CotpMetrics metrics;

    public MetricsCotpChallengeGenerator(CotpChallengeGenerator delegate, CotpMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** @return the wrapped generator. */
    public CotpChallengeGenerator delegate() {
        return delegate;
    }

    public CotpPolicy policy() {
        return delegate.policy();
    }

    public String newChallengeId() {
        return delegate.newChallengeId();
    }

    public CotpChallenge newChallenge(CotpCard card, Instant now, BurnStore burnStore) {
        return timed(() -> delegate.newChallenge(card, now, burnStore));
    }

    public CotpChallenge newChallenge(CotpCard card, Instant now, BurnStore burnStore, String context) {
        return timed(() -> delegate.newChallenge(card, now, burnStore, context));
    }

    public CotpChallenge random(CotpCard card, Instant now, BurnStore burnStore) {
        return timed(() -> delegate.random(card, now, burnStore));
    }

    public CotpChallenge random(CotpCard card, Instant now, BurnStore burnStore, String context) {
        return timed(() -> delegate.random(card, now, burnStore, context));
    }

    public CotpChallenge timeBased(CotpCard card, Instant now) {
        return timed(() -> delegate.timeBased(card, now));
    }

    public CotpChallenge timeBased(CotpCard card, Instant now, String context) {
        return timed(() -> delegate.timeBased(card, now, context));
    }

    public CotpChallenge timeBased(CotpCard card, Instant now, int stepOffset) {
        return timed(() -> delegate.timeBased(card, now, stepOffset));
    }

    private CotpChallenge timed(Supplier<CotpChallenge> call) {
        try {
            return call.get();
        } finally {
            metrics.recordOperation("challenge");
        }
    }
}
