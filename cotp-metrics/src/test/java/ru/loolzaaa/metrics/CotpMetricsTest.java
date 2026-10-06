package ru.loolzaaa.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ru.loolzaaa.VerificationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CotpMetrics}: latency histograms, gauges and the non-event counters.
 */
class CotpMetricsTest {

    private SimpleMeterRegistry registry;
    private CotpMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = CotpMetrics.create(registry, "5x5");
    }

    @Test
    void verificationDurationHistogramRecords() {
        metrics.recordVerificationDuration("kiosk_login", VerificationResult.OK, Duration.ofMillis(42));
        metrics.recordVerificationDuration("kiosk_login", VerificationResult.OK, Duration.ofMillis(120));

        Timer timer = registry.find(MetricNames.VERIFICATION_DURATION_SECONDS)
                .tag(MetricsTags.TAG_CONTEXT, "kiosk_login")
                .tag(MetricsTags.TAG_RESULT, "OK")
                .timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
        assertEquals(0.162, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 1e-9);
    }

    @Test
    void verificationDurationSeparatesResults() {
        metrics.recordVerificationDuration("stepup", VerificationResult.INVALID_PIN, Duration.ofMillis(10));
        Timer timer = registry.find(MetricNames.VERIFICATION_DURATION_SECONDS)
                .tag(MetricsTags.TAG_CONTEXT, "stepup")
                .tag(MetricsTags.TAG_RESULT, "INVALID_PIN")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void gridGenerationHistogramRecords() {
        metrics.recordGridGenerationDuration(null, Duration.ofMillis(3));
        Timer timer = registry.find(MetricNames.GRID_GENERATION_DURATION_SECONDS)
                .tag(MetricsTags.TAG_LAYOUT, "5x5")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void collisionNonceDistributionRecords() {
        metrics.recordCollisionNonce("5x5", 2.0);
        DistributionSummary summary = registry.find(MetricNames.COLLISION_NONCE)
                .tag(MetricsTags.TAG_LAYOUT, "5x5")
                .summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
    }

    @Test
    void activeCardsGaugeIsSettable() {
        Gauge unset = registry.find(MetricNames.ACTIVE_CARDS).tag(MetricsTags.TAG_LAYOUT, "5x5").gauge();
        assertNull(unset);

        metrics.setActiveCards("5x5", 42);
        Gauge gauge = registry.find(MetricNames.ACTIVE_CARDS).tag(MetricsTags.TAG_LAYOUT, "5x5").gauge();
        assertNotNull(gauge);
        assertEquals(42.0, gauge.value(), 1e-9);

        metrics.setActiveCards("5x5", 41);
        assertEquals(41.0,
                registry.find(MetricNames.ACTIVE_CARDS).tag(MetricsTags.TAG_LAYOUT, "5x5").gauge().value(),
                1e-9);
    }

    @Test
    void challengeTtlGaugeIsSettable() {
        metrics.setChallengeTtlSeconds(120);
        Gauge gauge = registry.find(MetricNames.CHALLENGE_TTL_SECONDS).gauge();
        assertNotNull(gauge);
        assertEquals(120.0, gauge.value(), 1e-9);
    }

    @Test
    void lifecycleCountersIncrement() {
        metrics.recordEnrollment(null);
        metrics.recordReissue("lost_card");
        metrics.recordRevocation("compromised");
        metrics.recordSecretDecryptFailure();
        metrics.recordOperation("enroll");

        assertEquals(1.0, counter(MetricNames.ENROLLMENT_TOTAL, MetricsTags.TAG_LAYOUT, "5x5"));
        assertEquals(1.0, counter(MetricNames.REISSUE_TOTAL, MetricsTags.TAG_REASON, "lost_card"));
        assertEquals(1.0, counter(MetricNames.REVOCATION_TOTAL, MetricsTags.TAG_REASON, "compromised"));
        assertEquals(1.0, counter(MetricNames.SECRET_DECRYPT_FAILURES_TOTAL));
        assertEquals(1.0, counter(MetricNames.OPERATIONS_TOTAL, MetricsTags.TAG_OPERATION, "enroll"));
    }

    @Test
    void eventSinkIsStableAndBoundToRegistry() {
        assertSame(metrics.eventSink(), metrics.eventSink());
        assertSame(registry, metrics.registry());
        assertEquals("5x5", metrics.defaultLayout());
    }

    private double counter(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        assertNotNull(c, () -> "counter not found: " + name);
        return c.count();
    }
}
