package ru.loolzaaa;

/**
 * Thrown when a {@link CotpScheme}, {@link CotpPolicy}, {@link WordDictionary} or
 * {@link CotpCredential} is misconfigured (invalid layout, non-power-of-two dictionary, blank
 * version, forbidden combinations, ...).
 *
 * <p>It extends {@link IllegalArgumentException} because configuration problems surface as invalid
 * arguments at construction time. Runtime/operational failures use {@link CotpException}
 * subclasses ({@link CotpCryptoException}, {@link CardExhaustedException}).</p>
 */
public class CotpConfigurationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CotpConfigurationException(String message) {
        super(message);
    }

    public CotpConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
