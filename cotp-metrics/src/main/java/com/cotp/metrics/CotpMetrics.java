package com.cotp.metrics;

import com.cotp.CotpCardGenerator;
import com.cotp.CotpChallengeGenerator;
import com.cotp.CotpEventSink;
import com.cotp.CotpVerifier;
import com.cotp.VerificationResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Facade that registers the COTP meter set against a Micrometer {@link MeterRegistry} and wires the
 * core audit events into it.
 *
 * <p>It exposes {@link #eventSink()} (a bound {@link CotpEventSink}) so the core can stay fully
 * dependency-free:</p>
 * <pre>{@code
 * CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
 * Cotp cotp = new Cotp(scheme, policy, metrics.eventSink());
 * }</pre>
 *
 * <p>Event-driven counters (issued/failed/burned/lockout/...) are emitted automatically through the
 * sink. Meters that the core cannot observe from a single event (latency histograms, gauges,
 * enrollment/reissue/revocation and KMS failure counters) are exposed as explicit methods or via
 * the optional decorators ({@link #verifier(CotpVerifier)}, {@link #cardGenerator(CotpCardGenerator)},
 * {@link #challengeGenerator(CotpChallengeGenerator)}).</p>
 *
 * <p><b>[НОРМА]</b> Only categorical tags are used; identifiers and secrets are never tags.</p>
 */
public final class CotpMetrics {

    /** Metric name prefix (see {@link MetricNames#PREFIX}). */
    public static final String PREFIX = MetricNames.PREFIX;

    private static final Duration[] VERIFICATION_SLO = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(2)
    };

    private static final Duration[] GRID_GENERATION_SLO = {
            Duration.ofNanos(100_000), Duration.ofNanos(500_000), Duration.ofMillis(1),
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100)
    };

    private final MeterRegistry registry;
    private final String defaultLayout;
    private final MetricsEventSink eventSink;
    private final Map<String, AtomicLong> activeCards = new ConcurrentHashMap<>();
    private final AtomicReference<Double> challengeTtlSeconds = new AtomicReference<>(0.0);

    private CotpMetrics(Builder builder) {
        this.registry = builder.registry;
        this.defaultLayout = MetricsTags.layout(builder.layout);
        this.eventSink = new MetricsEventSink(registry, defaultLayout);
        registry.gauge(MetricNames.CHALLENGE_TTL_SECONDS, challengeTtlSeconds,
                ref -> ref.get() == null ? 0.0 : ref.get());
    }

    /**
     * @param registry Micrometer registry to register meters against
     * @return a metrics facade using the {@code "unknown"} default layout
     */
    public static CotpMetrics create(MeterRegistry registry) {
        return builder(registry).build();
    }

    /**
     * @param registry Micrometer registry to register meters against
     * @param layout   default layout label applied to layout-tagged metrics
     */
    public static CotpMetrics create(MeterRegistry registry, String layout) {
        return builder(registry).layout(layout).build();
    }

    /** @return a builder bound to {@code registry}. */
    public static Builder builder(MeterRegistry registry) {
        return new Builder(registry);
    }

    /** @return the registry this facade registers meters against. */
    public MeterRegistry registry() {
        return registry;
    }

    /** @return the normalized default layout label. */
    public String defaultLayout() {
        return defaultLayout;
    }

    /** @return a {@link CotpEventSink} that feeds this facade's meters. */
    public CotpEventSink eventSink() {
        return eventSink;
    }

    // ---------------------------------------------------------------------
    // Decorators
    // ---------------------------------------------------------------------

    /** @return a timing decorator around the given verifier. */
    public MetricsCotpVerifier verifier(CotpVerifier delegate) {
        return new MetricsCotpVerifier(delegate, this);
    }

    /** @return a timing decorator around the given card generator. */
    public MetricsCotpCardGenerator cardGenerator(CotpCardGenerator delegate) {
        return new MetricsCotpCardGenerator(delegate, this);
    }

    /** @return an operation-counting decorator around the given challenge generator. */
    public MetricsCotpChallengeGenerator challengeGenerator(CotpChallengeGenerator delegate) {
        return new MetricsCotpChallengeGenerator(delegate, this);
    }

    // ---------------------------------------------------------------------
    // Recording helpers for non-event meters
    // ---------------------------------------------------------------------

    /**
     * Records a single verification latency observation.
     *
     * @param context  audit context (may be {@code null})
     * @param result   verification outcome (may be {@code null})
     * @param duration measured latency
     */
    public void recordVerificationDuration(String context, VerificationResult result, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        Timer.builder(MetricNames.VERIFICATION_DURATION_SECONDS)
                .description("COTP verification latency")
                .tag(MetricsTags.TAG_CONTEXT, MetricsTags.context(context))
                .tag(MetricsTags.TAG_RESULT, MetricsTags.result(result))
                .serviceLevelObjectives(VERIFICATION_SLO)
                .register(registry)
                .record(duration);
    }

    /**
     * Records a single grid-generation latency observation.
     *
     * @param layout   layout label (may be {@code null}, defaults to this facade's layout)
     * @param duration measured latency
     */
    public void recordGridGenerationDuration(String layout, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        Timer.builder(MetricNames.GRID_GENERATION_DURATION_SECONDS)
                .description("COTP grid generation latency")
                .tag(MetricsTags.TAG_LAYOUT, layout == null ? defaultLayout : MetricsTags.layout(layout))
                .serviceLevelObjectives(GRID_GENERATION_SLO)
                .register(registry)
                .record(duration);
    }

    /** Increments the card enrollment counter for {@code layout}. */
    public void recordEnrollment(String layout) {
        registry.counter(MetricNames.ENROLLMENT_TOTAL,
                MetricsTags.TAG_LAYOUT, layout == null ? defaultLayout : MetricsTags.layout(layout)).increment();
    }

    /** Increments the card reissue counter for {@code reason}. */
    public void recordReissue(String reason) {
        registry.counter(MetricNames.REISSUE_TOTAL,
                MetricsTags.TAG_REASON, MetricsTags.reason(reason)).increment();
    }

    /** Increments the card revocation counter for {@code reason}. */
    public void recordRevocation(String reason) {
        registry.counter(MetricNames.REVOCATION_TOTAL,
                MetricsTags.TAG_REASON, MetricsTags.reason(reason)).increment();
    }

    /** Increments the secret decryption / KMS failure counter. */
    public void recordSecretDecryptFailure() {
        registry.counter(MetricNames.SECRET_DECRYPT_FAILURES_TOTAL).increment();
    }

    /** Increments the general operation counter for {@code operation}. */
    public void recordOperation(String operation) {
        registry.counter(MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, MetricsTags.operation(operation)).increment();
    }

    /**
     * Records a collision-nonce observation (distribution of the deterministic duplicate-word
     * resolution nonce, an indicator of dictionary pressure).
     */
    public void recordCollisionNonce(String layout, double nonce) {
        DistributionSummary.builder(MetricNames.COLLISION_NONCE)
                .description("COTP duplicate-word collision nonce distribution")
                .tag(MetricsTags.TAG_LAYOUT, layout == null ? defaultLayout : MetricsTags.layout(layout))
                .publishPercentileHistogram()
                .register(registry)
                .record(nonce);
    }

    /**
     * Sets the number of active cards for a layout.
     *
     * @param layout layout label (may be {@code null}, defaults to this facade's layout)
     * @param count  current number of active cards
     */
    public void setActiveCards(String layout, long count) {
        String key = layout == null ? defaultLayout : MetricsTags.layout(layout);
        AtomicLong holder = activeCards.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong();
            registry.gauge(MetricNames.ACTIVE_CARDS, Tags.of(MetricsTags.TAG_LAYOUT, k), value);
            return value;
        });
        holder.set(count);
    }

    /**
     * Sets the configured challenge TTL gauge.
     *
     * @param seconds TTL in seconds
     */
    public void setChallengeTtlSeconds(double seconds) {
        challengeTtlSeconds.set(seconds);
    }

    /** Mutable builder for {@link CotpMetrics}. */
    public static final class Builder {
        private final MeterRegistry registry;
        private String layout = MetricsTags.UNKNOWN;

        private Builder(MeterRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        /** Sets the default layout label applied to layout-tagged metrics. */
        public Builder layout(String layout) {
            this.layout = layout;
            return this;
        }

        /** @return an immutable metrics facade. */
        public CotpMetrics build() {
            return new CotpMetrics(this);
        }
    }
}
