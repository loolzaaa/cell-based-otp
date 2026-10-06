package ru.loolzaaa.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ru.loolzaaa.Cotp;
import ru.loolzaaa.CotpCard;
import ru.loolzaaa.CotpCardGenerator;
import ru.loolzaaa.CotpChallenge;
import ru.loolzaaa.CotpPolicy;
import ru.loolzaaa.CotpScheme;
import ru.loolzaaa.VerificationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * End-to-end wiring tests: a real {@link Cotp} instance fed into a {@link SimpleMeterRegistry}
 * through {@link CotpMetrics}, plus the optional decorators.
 */
class CotpMetricsIntegrationTest {

    @Test
    void fullLifecycleProducesExpectedCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
        Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());

        CotpCard card = cotp.generateCard("CARD-1");
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-01-01T12:00:00Z");

        CotpChallenge ok = cotp.challengeGenerator()
                .newChallenge(card, now, cotp.verifier().burnStore(), "kiosk_login");
        assertEquals(VerificationResult.OK, cotp.verifier().verify(card, pinHash, ok,
                card.wordAt(ok.cellId()), "1234".toCharArray(), now));

        CotpChallenge bad = cotp.challengeGenerator()
                .newChallenge(card, now, cotp.verifier().burnStore(), "kiosk_login");
        assertEquals(VerificationResult.INVALID_WORD, cotp.verifier().verify(card, pinHash, bad,
                "definitely-not-a-card-word", "1234".toCharArray(), now));

        assertEquals(2.0, counter(registry, MetricNames.CHALLENGE_ISSUED_TOTAL,
                MetricsTags.TAG_MODE, "RANDOM", MetricsTags.TAG_CONTEXT, "kiosk_login"));
        assertEquals(1.0, counter(registry, MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "OK", MetricsTags.TAG_CONTEXT, "kiosk_login"));
        assertEquals(1.0, counter(registry, MetricNames.VERIFICATION_TOTAL,
                MetricsTags.TAG_RESULT, "INVALID_WORD", MetricsTags.TAG_CONTEXT, "kiosk_login"));
        assertEquals(1.0, counter(registry, MetricNames.CELL_BURNED_TOTAL,
                MetricsTags.TAG_LAYOUT, "5x5"));
    }

    @Test
    void verifierDecoratorRecordsLatency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
        Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());
        MetricsCotpVerifier verifier = metrics.verifier(cotp.verifier());

        CotpCard card = cotp.generateCard("CARD-2");
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        CotpChallenge ch = cotp.challengeGenerator()
                .newChallenge(card, now, cotp.verifier().burnStore(), "stepup");

        assertEquals(VerificationResult.OK, verifier.verify(card, pinHash, ch,
                card.wordAt(ch.cellId()), "1234".toCharArray(), now));

        Timer timer = registry.find(MetricNames.VERIFICATION_DURATION_SECONDS)
                .tag(MetricsTags.TAG_CONTEXT, "stepup")
                .tag(MetricsTags.TAG_RESULT, "OK")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(1.0, counter(registry, MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, "verify"));
    }

    @Test
    void cardGeneratorDecoratorRecordsGridGeneration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
        MetricsCotpCardGenerator generator =
                metrics.cardGenerator(new CotpCardGenerator(CotpScheme.defaults()));

        assertNotNull(generator.generate("CARD-3"));

        Timer timer = registry.find(MetricNames.GRID_GENERATION_DURATION_SECONDS)
                .tag(MetricsTags.TAG_LAYOUT, "5x5")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(1.0, counter(registry, MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, "generate"));
    }

    private static double counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, () -> "counter not found: " + name);
        return counter.count();
    }
}
