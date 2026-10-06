package ru.loolzaaa;

import java.util.Base64;

/**
 * Abstraction for protecting the {@code card_secret} at rest. The server must persist only the
 * (encrypted) secret, therefore callers seal it with a {@code SecretCipher} on enrolment and unseal
 * it on verification.
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link AesGcmSecretCipher} — local AES-256-GCM with a key held by the application
 *       (suitable when the key is stored in a KMS/HSM and injected at start-up);</li>
 *   <li>a KMS/HSM adapter — for example AWS KMS / Azure Key Vault / HashiCorp Vault, which
 *       implements this same interface by delegating to {@code Encrypt}/{@code Decrypt}.</li>
 * </ul>
 *
 * <p>The interface deliberately depends only on the JDK. A KMS adapter lives in a separate
 * optional module with the vendor SDK as its dependency, keeping the core dependency-free.</p>
 */
public interface SecretCipher {

    /**
     * @param plaintext raw secret bytes
     * @return self-contained ciphertext (implementation-defined framing, e.g. IV + ciphertext)
     */
    byte[] encrypt(byte[] plaintext);

    /**
     * @param ciphertext value previously produced by {@link #encrypt(byte[])}
     * @return the original plaintext
     */
    byte[] decrypt(byte[] ciphertext);

    /** Convenience: {@code encrypt} then Base64. */
    default String encryptToBase64(byte[] plaintext) {
        return Base64.getEncoder().encodeToString(encrypt(plaintext));
    }

    /** Convenience: Base64 decode then {@code decrypt}. */
    default byte[] decryptFromBase64(String ciphertextBase64) {
        return decrypt(Base64.getDecoder().decode(ciphertextBase64));
    }
}
