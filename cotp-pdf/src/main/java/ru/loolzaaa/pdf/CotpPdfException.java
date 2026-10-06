package ru.loolzaaa.pdf;

/**
 * Runtime exception thrown when a COTP card cannot be rendered to PDF.
 *
 * <p>{@link CotpCardPdf#render(ru.loolzaaa.CotpCard)} does not declare checked exceptions; the
 * underlying {@code IOException}/{@code WriterException} is wrapped here.</p>
 */
public class CotpPdfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable description
     * @param cause   underlying failure
     */
    public CotpPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
