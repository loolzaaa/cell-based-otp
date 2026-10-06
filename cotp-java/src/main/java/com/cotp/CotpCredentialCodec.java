package com.cotp;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Versioned, human-transportable encoding of a {@link CotpCredential} (card id, scheme version,
 * secret, validity metadata) with a CRC-32 checksum to catch transcription errors.
 *
 * <p>Format (single line, UTF-8):</p>
 * <pre>COTP1|&lt;cardId&gt;|&lt;schemeId&gt;|&lt;secretBase32&gt;|&lt;createdEpochMillis|-&gt;|&lt;expiresEpochMillis|-&gt;|&lt;crc32hex&gt;</pre>
 *
 * <p><b>Security:</b> the encoded value contains the raw {@code card_secret}. It MUST be stored
 * encrypted at rest (see {@link SecretCipher}) and never committed/logged. The checksum is an
 * integrity/typo guard, not a MAC.</p>
 */
public final class CotpCredentialCodec {

    /** Format marker/version. */
    public static final String PREFIX = "COTP1";

    private static final char FIELD_SEP = '|';

    private CotpCredentialCodec() {
    }

    /**
     * @param credential credential to encode
     * @return the encoded string (contains the secret in the clear)
     */
    public static String encode(CotpCredential credential) {
        Objects.requireNonNull(credential, "credential");
        String payload = PREFIX + FIELD_SEP
                + credential.cardId() + FIELD_SEP
                + credential.schemeId() + FIELD_SEP
                + credential.secret().toBase32() + FIELD_SEP
                + formatInstant(credential.createdAt()) + FIELD_SEP
                + formatInstant(credential.expiresAt());
        return payload + FIELD_SEP + String.format(Locale.ROOT, "%08x", crc32(payload));
    }

    /**
     * @param encoded value previously produced by {@link #encode(CotpCredential)}
     * @return the decoded credential
     * @throws CotpException if the value is malformed, has a bad checksum, or an unknown prefix
     */
    public static CotpCredential decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new CotpException("Encoded credential must not be blank");
        }
        String value = encoded.trim();
        int lastSep = value.lastIndexOf(FIELD_SEP);
        if (lastSep < 0) {
            throw new CotpException("Malformed encoded credential (missing fields)");
        }
        String payload = value.substring(0, lastSep);
        String checksum = value.substring(lastSep + 1);
        String expected = String.format(Locale.ROOT, "%08x", crc32(payload));
        if (!expected.equalsIgnoreCase(checksum)) {
            throw new CotpException("Encoded credential checksum mismatch (corrupted or mistyped)");
        }
        String[] parts = payload.split("\\" + FIELD_SEP, -1);
        if (parts.length != 6 || !PREFIX.equals(parts[0])) {
            throw new CotpException("Unsupported or malformed encoded credential");
        }
        String cardId = parts[1];
        String schemeId = parts[2];
        CotpSecret secret;
        try {
            secret = CotpSecret.fromBase32(parts[3]);
        } catch (RuntimeException e) {
            throw new CotpException("Invalid Base32 secret in encoded credential", e);
        }
        return CotpCredential.builder()
                .cardId(cardId)
                .schemeId(schemeId)
                .secret(secret)
                .createdAt(parseInstant(parts[4]))
                .expiresAt(parseInstant(parts[5]))
                .build();
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "-" : Long.toString(instant.toEpochMilli());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new CotpException("Invalid timestamp in encoded credential: '" + value + "'", e);
        }
    }

    private static long crc32(String payload) {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
