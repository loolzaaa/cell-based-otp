package ru.loolzaaa.metrics;

import ru.loolzaaa.CotpEvent;
import ru.loolzaaa.CotpEventSink;
import ru.loolzaaa.VerificationResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * A {@link CotpEventSink} that translates every {@link CotpEvent} emitted by the COTP core into
 * Micrometer meters.
 *
 * <p>This is the recommended integration point: it consumes the audit stream from {@code [P1-A]}
 * without duplicating any verification logic and without adding a runtime dependency to the
 * zero-dependency {@code cotp-java} core.</p>
 *
 * <p>Typical wiring:</p>
 * <pre>{@code
 * MeterRegistry registry = new SimpleMeterRegistry();          // or PrometheusMeterRegistry
 * CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
 * Cotp cotp = new Cotp(scheme, policy, metrics.eventSink());
 * }</pre>
 *
 * <p><b>Privacy / cardinality.</b> The event's {@code cardId} and {@code cellId} are deliberately
 * ignored: they are (PII and) unbounded values. Only categorical tags {@code mode}, {@code context},
 * {@code result} and the configured {@code layout} are used. Null contexts are normalized to
 * {@code "none"}.</p>
 *
 * <p>This class is thread-safe as long as the supplied {@link MeterRegistry} is.</p>
 */
public final class MetricsEventSink implements CotpEventSink {

    private final MeterRegistry registry;
    private final String layout;

    /**
     * Creates a sink using the {@code "unknown"} layout label.
     *
     * @param registry Micrometer registry (never {@code null})
     */
    public MetricsEventSink(MeterRegistry registry) {
        this(registry, MetricsTags.UNKNOWN);
    }

    /**
     * @param registry Micrometer registry (never {@code null})
     * @param layout   layout label applied to layout-tagged metrics; null/blank becomes
     *                 {@code "unknown"}
     */
    public MetricsEventSink(MeterRegistry registry, String layout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.layout = MetricsTags.layout(layout);
    }

    /** @return the normalized layout label used for this sink. */
    public String layout() {
        return layout;
    }

    @Override
    public void onEvent(CotpEvent event) {
        Objects.requireNonNull(event, "event");
        String context = MetricsTags.context(event.context());
        registry.counter(MetricNames.OPERATIONS_TOTAL,
                MetricsTags.TAG_OPERATION, MetricsTags.operation(event.type().name())).increment();

        switch (event.type()) {
            case CHALLENGE_ISSUED -> registry.counter(MetricNames.CHALLENGE_ISSUED_TOTAL,
                    MetricsTags.TAG_MODE, MetricsTags.mode(event.mode()),
                    MetricsTags.TAG_CONTEXT, context).increment();

            case VERIFY_SUCCEEDED -> registry.counter(MetricNames.VERIFICATION_TOTAL,
                    MetricsTags.TAG_RESULT, MetricsTags.result(event.result()),
                    MetricsTags.TAG_CONTEXT, context).increment();

            case VERIFY_FAILED -> {
                registry.counter(MetricNames.VERIFICATION_TOTAL,
                        MetricsTags.TAG_RESULT, MetricsTags.result(event.result()),
                        MetricsTags.TAG_CONTEXT, context).increment();
                VerificationResult result = event.result();
                if (result != null) {
                    switch (result) {
                        case INVALID_PIN -> registry.counter(MetricNames.PIN_VERIFY_FAILURES_TOTAL,
                                MetricsTags.TAG_CONTEXT, context).increment();
                        case IDENTITY_MISMATCH -> registry.counter(MetricNames.IDENTITY_MISMATCH_TOTAL,
                                MetricsTags.TAG_CONTEXT, context).increment();
                        case CHALLENGE_EXPIRED -> registry.counter(MetricNames.CHALLENGE_EXPIRED_TOTAL,
                                MetricsTags.TAG_CONTEXT, context).increment();
                        default -> {
                            // Other failures are covered by cotp_verification_total only.
                        }
                    }
                }
            }

            case CHALLENGE_REUSED_ATTEMPT -> {
                registry.counter(MetricNames.VERIFICATION_TOTAL,
                        MetricsTags.TAG_RESULT, MetricsTags.result(event.result()),
                        MetricsTags.TAG_CONTEXT, context).increment();
                registry.counter(MetricNames.CHALLENGE_REUSE_TOTAL).increment();
            }

            case CARD_EXHAUSTED -> {
                registry.counter(MetricNames.VERIFICATION_TOTAL,
                        MetricsTags.TAG_RESULT, MetricsTags.result(event.result()),
                        MetricsTags.TAG_CONTEXT, context).increment();
                registry.counter(MetricNames.CARDS_EXHAUSTED_TOTAL,
                        MetricsTags.TAG_LAYOUT, layout).increment();
            }

            case CELL_BURNED -> registry.counter(MetricNames.CELL_BURNED_TOTAL,
                    MetricsTags.TAG_LAYOUT, layout).increment();

            case CELL_COOLDOWN_RECORDED -> {
                // No dedicated counter in the [P1-B] table; retained in the operations total only.
            }

            case THROTTLE_LOCKOUT -> registry.counter(MetricNames.THROTTLE_LOCKOUTS_TOTAL,
                    MetricsTags.TAG_CONTEXT, context).increment();
        }
    }

    @Override
    public String toString() {
        return "MetricsEventSink{layout=" + layout + '}';
    }
}
