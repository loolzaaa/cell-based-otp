package ru.loolzaaa;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A COTP card secret (the only secret material the server has to store).
 *
 * <p>The secret is generated with a cryptographically secure RNG
 * ({@link SecureRandom}). It is used, via HKDF, to derive the grid key and the challenge key, and therefore to
 * deterministically regenerate the entire printed card. Nothing else (no grid, no words)
 * needs to be persisted.</p>
 *
 * <p><b>Entropy.</b> The default length is 32 bytes (256 bits); the minimum accepted by
 * {@link CotpScheme} is 16 bytes (128 bits), matching RFC 4226 R6. A 2048-word dictionary
 * encodes 11 bits per word, so the full 5&times;5 grid nominally carries 25&times;11 = 275 bits
 * (or 550 bits for the word-pair layout); the effective entropy is capped by the secret length.</p>
 */
public final class CotpSecret {

    private final byte[] bytes;

    private CotpSecret(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Generates a new random secret.
     *
     * @param lengthBytes number of secret bytes (recommended 32)
     * @return a new secret backed by a CSPRNG
     */
    public static CotpSecret generate(int lengthBytes) {
        if (lengthBytes < 16) {
            throw new IllegalArgumentException("Secret must be at least 16 bytes (128 bits), got " + lengthBytes);
        }
        byte[] b = new byte[lengthBytes];
        new SecureRandom().nextBytes(b);
        return new CotpSecret(b);
    }

    /**
     * Wraps existing secret bytes (defensive copy).
     *
     * @param bytes raw secret bytes
     * @return secret wrapper
     */
    public static CotpSecret of(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            throw new IllegalArgumentException("Secret must be at least 16 bytes (128 bits)");
        }
        return new CotpSecret(bytes.clone());
    }

    /**
     * Decodes a Base32 secret (padded or unpadded).
     *
     * @param base32 encoded secret
     * @return secret wrapper
     */
    public static CotpSecret fromBase32(String base32) {
        return of(Base32.decode(base32));
    }

    /** @return a defensive copy of the raw secret bytes. */
    public byte[] copyBytes() {
        return bytes.clone();
    }

    /** @return length of the secret in bytes. */
    public int length() {
        return bytes.length;
    }

    /** @return the secret encoded as unpadded Base32. */
    public String toBase32() {
        return Base32.encode(bytes);
    }

    /**
     * Best-effort zeroisation of the internal buffer. After calling this method the secret
     * must not be used again.
     */
    public void destroy() {
        Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CotpSecret other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "CotpSecret[len=" + bytes.length + "]";
    }
}
