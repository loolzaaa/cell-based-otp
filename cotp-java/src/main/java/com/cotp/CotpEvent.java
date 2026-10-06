package com.cotp;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable COTP event delivered to a {@link CotpEventSink} for audit and observability.
 *
 * <p><b>Privacy:</b> an event never contains the card secret, the printed word, the PIN or the
 * challenge id. It may contain the public {@code cardId} (printed on the card); sinks that must not
 * record that identifier should hash/drop it.</p>
 *
 * @param type    event kind, never {@code null}
 * @param at      event timestamp, never {@code null}
 * @param cardId  public card identifier (may be {@code null} for non-card events)
 * @param context free-form audit context supplied by the application (e.g. {@code "kiosk_login"},
 *                {@code "stepup"}); may be {@code null}
 * @param mode    challenge mode, when relevant; may be {@code null}
 * @param cellId  canonical cell id (e.g. {@code "C2"}), when relevant; may be {@code null}
 * @param result  verification result, for verification events; may be {@code null}
 */
public record CotpEvent(CotpEventType type, Instant at, String cardId, String context,
                        ChallengeMode mode, String cellId, VerificationResult result) {

    public CotpEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
    }

    @Override
    public String toString() {
        return "CotpEvent{" + type + ", at=" + at + ", cardId=" + cardId + ", context=" + context
                + ", mode=" + mode + ", cell=" + cellId + ", result=" + result + "}";
    }
}
