package ru.loolzaaa.metrics;

import ru.loolzaaa.Cell;
import ru.loolzaaa.CotpCard;
import ru.loolzaaa.CotpCardGenerator;
import ru.loolzaaa.CotpScheme;
import ru.loolzaaa.CotpSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Delegating decorator around {@link CotpCardGenerator} that records
 * {@code cotp_grid_generation_duration_seconds} (the HKDF + HMAC cost of materializing a grid) and
 * increments {@code cotp_operations_total}.
 *
 * <p>Enrollment itself should be counted explicitly via {@link CotpMetrics#recordEnrollment(String)},
 * because issue/reissue/revocation are application lifecycle events, not core audit events.</p>
 */
public final class MetricsCotpCardGenerator {

    private final CotpCardGenerator delegate;
    private final CotpMetrics metrics;

    public MetricsCotpCardGenerator(CotpCardGenerator delegate, CotpMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** @return the wrapped generator. */
    public CotpCardGenerator delegate() {
        return delegate;
    }

    public CotpScheme scheme() {
        return delegate.scheme();
    }

    public CotpCard generate(String cardId) {
        return timed(() -> delegate.generate(cardId));
    }

    public CotpCard generate(String cardId, CotpSecret secret) {
        return timed(() -> delegate.generate(cardId, secret));
    }

    public CotpCard generate(String cardId, CotpSecret secret, Instant createdAt, Instant expiresAt) {
        return timed(() -> delegate.generate(cardId, secret, createdAt, expiresAt));
    }

    public CotpCard derive(CotpCard card) {
        return timed(() -> delegate.derive(card));
    }

    /**
     * Not timed: this helper is used on the verification path, where latency is already captured by
     * {@code cotp_verification_duration_seconds}.
     */
    public String wordFor(String cardId, CotpSecret secret, Cell cell) {
        return delegate.wordFor(cardId, secret, cell);
    }

    private <T> T timed(Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            metrics.recordGridGenerationDuration(metrics.defaultLayout(),
                    Duration.ofNanos(System.nanoTime() - start));
            metrics.recordOperation("generate");
        }
    }
}
