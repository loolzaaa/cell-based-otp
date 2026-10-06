package com.cotp.pdf;

import com.cotp.CellPosition;
import com.cotp.CotpCard;
import com.cotp.CotpCardGenerator;
import com.cotp.CotpScheme;
import com.cotp.CotpSecret;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CotpCardPdfTest {

    private static final String PDF_MAGIC = "%PDF-";

    private static CotpSecret fixedSecret() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return CotpSecret.of(bytes);
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersSingleWordCardWithExpectedContent() throws IOException {
        CotpScheme scheme = CotpScheme.builder().build();
        CotpCard card = new CotpCardGenerator(scheme).generate("CARD-123", fixedSecret(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));

        byte[] pdf = new CotpCardPdf().render(card);

        assertTrue(pdf.length > 0, "PDF must not be empty");
        assertEquals(PDF_MAGIC, new String(pdf, 0, PDF_MAGIC.length(), StandardCharsets.US_ASCII));

        try (PDDocument doc = PDDocument.load(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);

            assertTrue(text.contains("CARD-123"), text);
            assertTrue(text.contains(card.wordAt("A1")), text);
            assertTrue(text.contains(card.wordAt("E5")), text);
            assertTrue(text.contains("Version: COTP/v1"), text);
            assertTrue(text.contains("2026-01-01"), text);
            assertTrue(text.contains("2027-01-01"), text);
            assertTrue(text.contains("PIN"), text);
            for (char c : scheme.columns()) {
                assertTrue(text.contains(String.valueOf(c)), "missing header " + c + " in:\n" + text);
            }
            for (int r : scheme.rows()) {
                assertTrue(text.contains(String.valueOf(r)), "missing row " + r + " in:\n" + text);
            }
        }
    }

    @Test
    void rendersTopBottomWordPairCard() throws IOException {
        CotpScheme scheme = CotpScheme.builder()
                .positions(List.of(CellPosition.TOP, CellPosition.BOTTOM))
                .build();
        CotpCard card = new CotpCardGenerator(scheme).generate("PAIR-9", fixedSecret());

        byte[] pdf = new CotpCardPdf().render(card);

        try (PDDocument doc = PDDocument.load(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains(card.wordAt("C1T")), text);
            assertTrue(text.contains(card.wordAt("C1B")), text);
            assertTrue(text.contains("TOP"), text);
            assertTrue(text.contains("BOTTOM"), text);
            assertTrue(text.contains("PAIR-9"), text);
        }
    }

    @Test
    void rendersWithoutExpiry() throws IOException {
        CotpScheme scheme = CotpScheme.builder().build();
        CotpCard card = new CotpCardGenerator(scheme).generate("NOEXP", fixedSecret());

        String text = extractText(new CotpCardPdf().render(card));

        assertTrue(text.contains("Expires: n/a"), text);
        assertTrue(text.contains("Generated: n/a"), text);
    }

    @Test
    void writeProducesAValidPdf() throws IOException {
        CotpScheme scheme = CotpScheme.builder().build();
        CotpCard card = new CotpCardGenerator(scheme).generate("WRITE-1", fixedSecret());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CotpCardPdf().write(card, out);

        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0);
        assertEquals(PDF_MAGIC, new String(bytes, 0, PDF_MAGIC.length(), StandardCharsets.US_ASCII));
    }

    @Test
    void customOptionsControlPageSizeAndTitle() throws IOException {
        CotpPdfOptions options = CotpPdfOptions.builder()
                .title("My COTP Badge")
                .pageWidth(300f)
                .pageHeight(420f)
                .build();
        CotpScheme scheme = CotpScheme.builder().build();
        CotpCard card = new CotpCardGenerator(scheme).generate("OPT-1", fixedSecret());

        byte[] pdf = new CotpCardPdf(options).render(card);

        try (PDDocument doc = PDDocument.load(pdf)) {
            PDRectangle mediaBox = doc.getPage(0).getMediaBox();
            assertEquals(300f, mediaBox.getWidth(), 0.01f);
            assertEquals(420f, mediaBox.getHeight(), 0.01f);
            assertTrue(new PDFTextStripper().getText(doc).contains("My COTP Badge"));
        }
    }
}
