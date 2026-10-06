package ru.loolzaaa.pdf;

import ru.loolzaaa.CellPosition;
import ru.loolzaaa.CotpCard;
import ru.loolzaaa.CotpCardGenerator;
import ru.loolzaaa.CotpScheme;
import ru.loolzaaa.CotpSecret;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Renders a deterministic sample {@link CotpCard} to a real PDF file for visual inspection.
 *
 * <p>The secret, card id and validity timestamps are fixed, so the generated grid (and therefore
 * the PDF) is fully reproducible. Only public data is written to the PDF.</p>
 *
 * <p>Optional arguments, in order:</p>
 * <ol>
 *   <li>output path (default {@code cotp-pdf/build/sample-card.pdf});</li>
 *   <li>layout: {@code SINGLE} or {@code WORD_PAIRS} (default {@code SINGLE});</li>
 *   <li>card id (default {@code COTP-DEMO-0001});</li>
 *   <li>optional PIN note printed as the card title (must NOT be the real PIN).</li>
 * </ol>
 */
public final class SampleCardPdf {

    /** Default card id used by the sample generator. */
    public static final String DEFAULT_CARD_ID = "COTP-DEMO-0001";

    private static final String DEFAULT_OUTPUT = "cotp-pdf/build/sample-card.pdf";
    private static final byte[] FIXED_SECRET = fixedSecret();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2027-01-01T00:00:00Z");

    private SampleCardPdf() {
    }

    /**
     * Generates the sample PDF.
     *
     * @param args optional output path, layout, card id and PIN note (see class Javadoc)
     */
    public static void main(String[] args) {
        if (args.length > 4) {
            System.err.println(usage());
            System.exit(2);
            return;
        }

        String outputArg = args.length > 0 ? args[0] : DEFAULT_OUTPUT;
        String layoutArg = args.length > 1 ? args[1] : "SINGLE";
        String cardId = args.length > 2 ? args[2] : DEFAULT_CARD_ID;
        String pinNote = args.length > 3 ? args[3] : null;

        CotpScheme scheme;
        try {
            scheme = schemeFor(layoutArg);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(usage());
            System.exit(2);
            return;
        }

        CotpCard card = new CotpCardGenerator(scheme)
                .generate(cardId, CotpSecret.of(FIXED_SECRET), CREATED_AT, EXPIRES_AT);

        CotpPdfOptions.Builder options = CotpPdfOptions.builder();
        if (pinNote != null && !pinNote.isBlank()) {
            options.title(pinNote);
        }
        byte[] pdf = new CotpCardPdf(options.build()).render(card);

        Path output = Path.of(outputArg).toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(output)) {
                out.write(pdf);
            }
        } catch (IOException e) {
            System.err.println("Failed to write PDF to " + output + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Wrote COTP sample card PDF");
        System.out.println("  path:   " + output);
        System.out.println("  bytes:  " + pdf.length);
        System.out.println("  pages:  " + pageCount(pdf));
        System.out.println("  layout: " + scheme.positions());
        System.out.println("  cardId: " + cardId);
        System.out.println("  magic:  " + new String(pdf, 0, "%PDF-".length(), StandardCharsets.US_ASCII));
    }

    static CotpScheme schemeFor(String layout) {
        String normalized = layout.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SINGLE" -> CotpScheme.builder().build();
            case "WORD_PAIRS", "PAIRS", "TOP_BOTTOM" -> CotpScheme.builder()
                    .positions(List.of(CellPosition.TOP, CellPosition.BOTTOM))
                    .build();
            default -> throw new IllegalArgumentException(
                    "Unknown layout '" + layout + "'; expected SINGLE or WORD_PAIRS");
        };
    }

    private static int pageCount(byte[] pdf) {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return -1;
        }
    }

    private static byte[] fixedSecret() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }

    private static String usage() {
        return "Usage: SampleCardPdf [outputPath] [SINGLE|WORD_PAIRS] [cardId] [pinNote]";
    }
}
