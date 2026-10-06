package com.cotp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Minimal HMAC-SHA-256 helper built directly on the JDK JCE provider.
 * No external dependencies are required.
 */
final class Hmac {

    /** JCE algorithm name for HMAC-SHA-256. */
    static final String HMAC_SHA256 = "HmacSHA256";

    private Hmac() {
    }

    /**
     * Computes {@code HMAC-SHA-256(key, message)}.
     *
     * @param key     raw HMAC key (any non-empty length; RFC 2104 permits arbitrary length)
     * @param message message bytes
     * @return 32-byte MAC
     */
    static byte[] sha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, "RAW"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CotpCryptoException("HMAC-SHA-256 is unavailable in this JVM", e);
        }
    }
}
