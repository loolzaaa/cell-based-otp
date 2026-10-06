package com.cotp;

import java.util.Objects;

/**
 * HKDF (HMAC-based Extract-and-Expand Key Derivation Function) as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc5869">RFC 5869</a> with HMAC-SHA-256.
 *
 * <p>COTP uses HKDF to derive independent sub-keys from a single card secret
 * (for example {@code K_grid} for the printed grid and {@code K_challenge} for
 * time-based challenge selection).</p>
 *
 * <p>This implementation is deliberately tiny and depends only on {@link Hmac}
 * (i.e. only on the JDK). It is validated against the official RFC 5869 test
 * vectors in the test-suite.</p>
 */
public final class Hkdf {

    private static final int HASH_LEN = 32;
    private static final int MAX_EXPAND_LEN = 255 * HASH_LEN;

    private Hkdf() {
    }

    /**
     * HKDF-Extract (RFC 5869 section 2.2):
     * {@code PRK = HMAC-Hash(salt, IKM)}.
     *
     * @param salt optional salt; {@code null} or empty means {@code HashLen} zero bytes
     * @param ikm  input keying material (the card secret)
     * @return pseudo-random key of {@value #HASH_LEN} bytes
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(ikm, "ikm");
        if (ikm.length == 0) {
            throw new IllegalArgumentException("IKM must not be empty");
        }
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return Hmac.sha256(effectiveSalt, ikm);
    }

    /**
     * HKDF-Expand (RFC 5869 section 2.3):
     * {@code T(i) = HMAC-Hash(PRK, T(i-1) | info | i)}, {@code OKM = first L bytes}.
     *
     * @param prk    pseudo-random key produced by {@link #extract}
     * @param info   optional context/application-specific info
     * @param length number of output bytes, {@code 1..8160}
     * @return derived key material of exactly {@code length} bytes
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(prk, "prk");
        if (prk.length < HASH_LEN) {
            throw new IllegalArgumentException("PRK must be at least " + HASH_LEN + " bytes (RFC 5869 section 2.3)");
        }
        if (length < 1 || length > MAX_EXPAND_LEN) {
            throw new IllegalArgumentException("length must be in [1, " + MAX_EXPAND_LEN + "], got " + length);
        }
        byte[] context = (info == null) ? new byte[0] : info;
        byte[] okm = new byte[length];
        byte[] previous = new byte[0];
        int generated = 0;
        int blockIndex = 1;
        while (generated < length) {
            byte[] input = new byte[previous.length + context.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(context, 0, input, previous.length, context.length);
            input[input.length - 1] = (byte) blockIndex;
            previous = Hmac.sha256(prk, input);
            int toCopy = Math.min(HASH_LEN, length - generated);
            System.arraycopy(previous, 0, okm, generated, toCopy);
            generated += toCopy;
            blockIndex++;
        }
        return okm;
    }

    /**
     * Convenience one-shot HKDF: {@code expand(extract(salt, ikm), info, length)}.
     *
     * @param ikm    input keying material
     * @param salt   optional salt
     * @param info   optional info
     * @param length number of output bytes
     * @return derived key material
     */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }
}
