package ru.loolzaaa;

/**
 * Unchecked exception thrown when a COTP operation cannot be completed
 * (misconfiguration, unavailable JCE algorithm, malformed encoding, etc.).
 */
public class CotpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CotpException(String message) {
        super(message);
    }

    public CotpException(String message, Throwable cause) {
        super(message, cause);
    }
}
