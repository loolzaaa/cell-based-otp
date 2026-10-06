package com.cotp;

import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted, iterated hashing of the COTP private PIN (the "something you know" factor),
 * using PBKDF2-HMAC-SHA256 from the JDK.
 *
 * <p>The verified hash is stored in a self-describing string:</p>
 * <pre>pbkdf2-sha256$&lt;iterations&gt;$&lt;base64 salt&gt;$&lt;base64 hash&gt;</pre>
 *
 * <p>The default iteration count comes from {@link CotpPolicy#pinIterations()} (210000, an
 * OWASP-recommended baseline for PBKDF2-HMAC-SHA256). Because a PIN has very low entropy,
 * PBKDF2 alone is not sufficient: online throttling/lockout is mandatory and is implemented by
 * the verification layer.</p>
 */
public final class PinHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param iterations PBKDF2 iteration count (should be >= 100000)
     */
    public PinHasher(int iterations) {
        if (iterations < 1000) {
            throw new IllegalArgumentException("iterations must be >= 1000");
        }
        this.iterations = iterations;
    }

    /** @return a hasher using the default iteration count (210000). */
    public static PinHasher withDefaults() {
        return new PinHasher(210_000);
    }

    /**
     * Hashes a PIN.
     *
     * @param pin PIN characters (not logged, not stored)
     * @return encoded hash string
     */
    public String hash(char[] pin) {
        Objects.requireNonNull(pin, "pin");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt, iterations, KEY_BITS);
        return PREFIX + "$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verifies a PIN against an encoded hash in constant time.
     *
     * @param pin     candidate PIN
     * @param encoded hash produced by {@link #hash(char[])}
     * @return {@code true} if the PIN matches
     */
    public boolean verify(char[] pin, String encoded) {
        Objects.requireNonNull(pin, "pin");
        if (encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iters = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(pin, salt, iters, expected.length * 8);
            return ConstantTime.equals(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] pin, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new CotpCryptoException("PBKDF2WithHmacSHA256 is unavailable in this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }
}
