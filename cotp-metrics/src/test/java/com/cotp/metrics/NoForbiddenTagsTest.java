package com.cotp.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.cotp.Cotp;
import com.cotp.CotpCard;
import com.cotp.CotpChallenge;
import com.cotp.CotpPolicy;
import com.cotp.CotpScheme;
import com.cotp.VerificationResult;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * [НОРМА] guard: no metric name or tag may carry an identifier (PII / high cardinality) or a
 * secret. This test runs a realistic flow through the real core and asserts that neither the
 * {@code cardId}, the challenged {@code cellId}, the printed {@code word} nor the {@code pin} of the
 * user leak into any generated time series.
 */
class NoForbiddenTagsTest {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "card_id", "cardid", "card_secret", "cardsecret",
            "employee_id", "employeeid", "challenge_id", "challengeid",
            "word", "pin", "secret");

    @Test
    void fullFlowNeverEmitsIdentifiersOrSecretsAsTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
        Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());

        String cardId = "CARD-IDENTIFIER-9000";
        String pin = "2468";
        CotpCard card = cotp.generateCard(cardId);
        String pinHash = cotp.newPinHash(pin.toCharArray());

        Instant now = Instant.now();

        CotpChallenge ok = cotp.challengeGenerator()
                .newChallenge(card, now, cotp.verifier().burnStore(), "kiosk_login");
        String word = card.wordAt(ok.cellId());
        cotp.verifier().verify(card, pinHash, ok, word, pin.toCharArray(), now);

        CotpChallenge bad = cotp.challengeGenerator()
                .newChallenge(card, now, cotp.verifier().burnStore(), null);
        cotp.verifier().verify(card, pinHash, bad, "not-a-real-card-word", "0000".toCharArray(), now);

        metrics.recordVerificationDuration("kiosk_login", VerificationResult.OK, Duration.ofMillis(5));
        metrics.recordGridGenerationDuration("5x5", Duration.ofMillis(1));
        metrics.setActiveCards("5x5", 1);

        Set<String> sensitive = new HashSet<>();
        sensitive.add(cardId);
        sensitive.add(ok.cellId());
        sensitive.add(bad.cellId());
        sensitive.add(word);
        sensitive.add(pin);
        sensitive.add("0000");

        assertNoForbiddenTags(registry, sensitive, cardId);
    }

    private static void assertNoForbiddenTags(SimpleMeterRegistry registry, Set<String> sensitive,
                                              String longIdentifier) {
        assertFalse(registry.getMeters().isEmpty(), "expected the flow to register meters");
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            String name = id.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_KEYS) {
                assertFalse(name.contains(forbidden),
                        () -> "metric name leaks forbidden key '" + forbidden + "': " + id.getName());
            }
            for (Tag tag : id.getTags()) {
                String key = tag.getKey().toLowerCase(Locale.ROOT);
                assertFalse(FORBIDDEN_KEYS.contains(key),
                        () -> "forbidden tag key: " + tag.getKey());
                assertFalse(key.equals("card_id") || key.equals("employee_id") || key.equals("challenge_id"),
                        () -> "identifier tag key: " + tag.getKey());
                for (String value : sensitive) {
                    assertFalse(tag.getValue().equals(value),
                            () -> "tag '" + tag.getKey() + "' equals sensitive value");
                }
                assertFalse(tag.getValue().contains(longIdentifier),
                        () -> "tag '" + tag.getKey() + "' contains the card id");
            }
        }
    }
}
