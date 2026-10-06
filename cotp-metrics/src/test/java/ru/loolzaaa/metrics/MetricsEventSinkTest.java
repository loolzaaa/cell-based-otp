package ru.loolzaaa.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ru.loolzaaa.ChallengeMode;
import ru.loolzaaa.CotpEvent;
import ru.loolzaaa.CotpEventType;
import ru.loolzaaa.VerificationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricsEventSink}: every relevant {@link CotpEventType} must increment the
 * expected counter with the expected categorical tags.
 */
class MetricsEventSinkTest {

    private SimpleMeterRegistry registry;
    private MetricsEventSink sink;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sink = new MetricsEventSink(registry, "5x5");
    }

    private static CotpEvent event(CotpEventType type, String context, ChallengeMode mode,
                                   VerificationResult result) {
        return new CotpEvent(type, Instant.parse("2026-01-01T00:00:00Z"),
                "CARD-IDENTIFIER-9000", context, mode, "Z9", result);
    }

    private double counter(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        assertNotNull(c, () -> "counter not found: " + name + " tags=" + String.join(",", tags));
        return c.count();
    }

    @Test
    void challengeIssuedIncrementsCounter() {
        sink.onEvent(event(CotpEventType.CHALLENGE_ISSUED, "kiosk_login", ChallengeMode.RANDOM, null));
        sink.onEvent(event(CotpEventType.CHALLENGE_ISSUED, "kiosk_login", ChallengeMode.RANDOM, null));
        sink.onEvent(event(CotpEventType.CHALLENGE_ISSUED, "stepup", ChallengeMode.TIME_BASED, null));

        assertEquals(2.0, counter(MetricNames.CHALLENGE_ISSUED_TOTAL,
                MetricsTags.TAG_MODE, "RANDOM", MetricsTags.TAG_CONTEXT, "kiosk_login"));
        assertEquals(1.0, counter(MetricNames.CHALLENGE_ISSUED_TOTAL,
                MetricsTags.TAG_MODE, "TIME_BASED", MetricsTags.TAG_CONTEXT, "stepup"));
    }

    @Test
    void verifySucceededIncrementsCounter() {
        sink.onEvent(event(CotpEventType.VERIFY_SUCCEEDED, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.OK));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "OK", MetricsTags.TAG_CONTEXT, "kiosk_login"));
    }

    @Test
    void verifyFailedIncrementsCounter() {
        sink.onEvent(event(CotpEventType.VERIFY_FAILED, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.INVALID_WORD));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "INVALID_WORD", MetricsTags.TAG_CONTEXT, "kiosk_login"));
    }

    @Test
    void invalidPinIncrementsPinFailureCounter() {
        sink.onEvent(event(CotpEventType.VERIFY_FAILED, "stepup", ChallengeMode.RANDOM,
                VerificationResult.INVALID_PIN));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "INVALID_PIN", MetricsTags.TAG_CONTEXT, "stepup"));
        assertEquals(1.0, counter(MetricNames.PIN_VERIFY_FAILURES_TOTAL,
                MetricsTags.TAG_CONTEXT, "stepup"));
    }

    @Test
    void identityMismatchIncrementsCounter() {
        sink.onEvent(event(CotpEventType.VERIFY_FAILED, "service_desk", ChallengeMode.RANDOM,
                VerificationResult.IDENTITY_MISMATCH));
        assertEquals(1.0, counter(MetricNames.IDENTITY_MISMATCH_TOTAL,
                MetricsTags.TAG_CONTEXT, "service_desk"));
    }

    @Test
    void challengeExpiredIncrementsCounter() {
        sink.onEvent(event(CotpEventType.VERIFY_FAILED, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.CHALLENGE_EXPIRED));
        assertEquals(1.0, counter(MetricNames.CHALLENGE_EXPIRED_TOTAL,
                MetricsTags.TAG_CONTEXT, "kiosk_login"));
    }

    @Test
    void challengeReuseIncrementsCounter() {
        sink.onEvent(event(CotpEventType.CHALLENGE_REUSED_ATTEMPT, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.CHALLENGE_REUSED));
        assertEquals(1.0, counter(MetricNames.CHALLENGE_REUSE_TOTAL));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "CHALLENGE_REUSED", MetricsTags.TAG_CONTEXT, "kiosk_login"));
    }

    @Test
    void cardExhaustedIncrementsCounter() {
        sink.onEvent(event(CotpEventType.CARD_EXHAUSTED, "service_desk", ChallengeMode.RANDOM,
                VerificationResult.CARD_EXHAUSTED));
        assertEquals(1.0, counter(MetricNames.CARDS_EXHAUSTED_TOTAL,
                MetricsTags.TAG_LAYOUT, "5x5"));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "CARD_EXHAUSTED", MetricsTags.TAG_CONTEXT, "service_desk"));
    }

    @Test
    void cellBurnedIncrementsCounter() {
        sink.onEvent(event(CotpEventType.CELL_BURNED, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.OK));
        assertEquals(1.0, counter(MetricNames.CELL_BURNED_TOTAL,
                MetricsTags.TAG_LAYOUT, "5x5"));
    }

    @Test
    void throttleLockoutIncrementsCounter() {
        sink.onEvent(event(CotpEventType.THROTTLE_LOCKOUT, "kiosk_login", null,
                VerificationResult.INVALID_PIN));
        assertEquals(1.0, counter(MetricNames.THROTTLE_LOCKOUTS_TOTAL,
                MetricsTags.TAG_CONTEXT, "kiosk_login"));
    }

    @Test
    void nullContextIsNormalizedToNone() {
        sink.onEvent(event(CotpEventType.VERIFY_FAILED, null, ChallengeMode.RANDOM,
                VerificationResult.INVALID_WORD));
        assertEquals(1.0, counter(MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "INVALID_WORD", MetricsTags.TAG_CONTEXT, "none"));
    }

    @Test
    void everyEventUpdatesOperationsTotal() {
        sink.onEvent(event(CotpEventType.CHALLENGE_ISSUED, "kiosk_login", ChallengeMode.RANDOM, null));
        sink.onEvent(event(CotpEventType.VERIFY_SUCCEEDED, "kiosk_login", ChallengeMode.RANDOM,
                VerificationResult.OK));
        assertEquals(1.0, counter(MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, "CHALLENGE_ISSUED"));
        assertEquals(1.0, counter(MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, "VERIFY_SUCCEEDED"));
    }
}
