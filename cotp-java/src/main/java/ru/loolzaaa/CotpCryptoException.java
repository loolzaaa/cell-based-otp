package ru.loolzaaa;

/**
 * Thrown when a cryptographic or encoding operation fails at runtime (JCE algorithm unavailable,
 * wrong key, tampered ciphertext, derivation failure). It is a {@link CotpException} subtype,
 * distinct from configuration errors ({@link CotpConfigurationException}).
 */
public class CotpCryptoException extends CotpException {

    private static final long serialVersionUID = 1L;

    public CotpCryptoException(String message) {
        super(message);
    }

    public CotpCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
