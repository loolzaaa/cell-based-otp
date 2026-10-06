package com.cotp;

/**
 * Receives {@link CotpEvent}s emitted by the COTP core for auditing, metrics and security
 * monitoring.
 *
 * <p>The default {@link #NOOP} sink discards everything, so the core has zero dependencies and zero
 * overhead unless a sink is supplied. A metrics module (see the project TODO, {@code cotp-metrics})
 * or an application audit logger implements this interface.</p>
 *
 * <p>Implementations MUST be thread-safe and SHOULD be fast and non-throwing: an event sink is on
 * the authentication path. Never log secrets, words or PINs.</p>
 */
@FunctionalInterface
public interface CotpEventSink {

    /** A sink that discards every event. */
    CotpEventSink NOOP = event -> {
    };

    /**
     * @param event the emitted event, never {@code null}
     */
    void onEvent(CotpEvent event);
}
