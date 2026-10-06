package ru.loolzaaa.pdf;

import ru.loolzaaa.Cell;
import ru.loolzaaa.CellPosition;
import ru.loolzaaa.CotpCard;
import ru.loolzaaa.CotpScheme;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders a {@link CotpCard} into a printable, badge/credit-card style PDF.
 *
 * <p>The produced page contains:</p>
 * <ul>
 *   <li>the 5&times;5 word grid with column headers {@code A}..{@code E} and row numbers
 *       {@code 1}..{@code 5}, supporting both the single-word and the TOP/BOTTOM word-pair
 *       layouts (driven by {@link CotpScheme#positions()});</li>
 *   <li>a QR code encoding the public {@link CotpCard#cardId() card id};</li>
 *   <li>card metadata (scheme version, creation date, expiry date if present);</li>
 *   <li>a short usage instruction.</li>
 * </ul>
 *
 * <p><b>Privacy.</b> Only the public card id, the non-secret metadata and the already-printed grid
 * words appear on the card. The {@link ru.loolzaaa.CotpSecret card secret} and any PII are never
 * written; {@link CotpCard} exposes no PII in the first place.</p>
 *
 * <p>The type is stateless and thread-safe. It uses only Apache PDFBox and ZXing (both
 * Apache-2.0); the {@code cotp-java} core remains dependency-free.</p>
 */
public final class CotpCardPdf {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final float WORD_FONT_SIZE = 8f;
    private static final float HEADER_FONT_SIZE = 9f;
    private static final float CELL_LINE_HEIGHT = 20f;
    private static final float PAIR_LINE_HEIGHT = 15f;
    private static final float HEADER_HEIGHT = 18f;
    private static final float ROW_HEADER_WIDTH = 18f;
    private static final float QR_DISPLAY_SIZE = 64f;

    private final CotpPdfOptions options;

    /** Creates a renderer with the {@link CotpPdfOptions#defaults() default options}. */
    public CotpCardPdf() {
        this(CotpPdfOptions.defaults());
    }

    /**
     * @param options rendering options (page geometry, title, QR resolution)
     */
    public CotpCardPdf(CotpPdfOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** @return the rendering options in use. */
    public CotpPdfOptions options() {
        return options;
    }

    /**
     * Renders the card into a new in-memory PDF document.
     *
     * @param card card to render
     * @return the complete PDF bytes (starts with {@code %PDF-})
     * @throws CotpPdfException if the document cannot be produced
     */
    public byte[] render(CotpCard card) {
        Objects.requireNonNull(card, "card");
        try (PDDocument doc = new PDDocument()) {
            PDRectangle pageSize = new PDRectangle(options.pageWidth(), options.pageHeight());
            PDPage page = new PDPage(pageSize);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                drawCard(doc, content, card, pageSize);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (CotpPdfException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CotpPdfException("Failed to render COTP card to PDF", e);
        }
    }

    /**
     * Renders the card and writes the PDF to {@code out}. The stream is flushed but not closed.
     *
     * @param card card to render
     * @param out  destination stream
     * @throws IOException if the stream cannot be written
     */
    public void write(CotpCard card, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        out.write(render(card));
        out.flush();
    }

    private void drawCard(PDDocument doc, PDPageContentStream cs, CotpCard card, PDRectangle page)
            throws IOException {
        float margin = options.margin();
        float x0 = margin;
        float contentWidth = page.getWidth() - 2f * margin;
        float cursor = page.getHeight() - margin;

        drawQr(doc, cs, card.cardId(),
                page.getWidth() - margin - QR_DISPLAY_SIZE, cursor - QR_DISPLAY_SIZE, QR_DISPLAY_SIZE);

        float titleSize = 15f;
        drawText(cs, PDType1Font.HELVETICA_BOLD, titleSize, options.title(), x0, cursor - titleSize);
        cursor -= titleSize + 8f;

        float idSize = 11f;
        drawText(cs, PDType1Font.HELVETICA_BOLD, idSize, "Card ID: " + card.cardId(), x0, cursor - idSize);
        cursor -= idSize + 8f;

        float metaSize = 8.5f;
        drawText(cs, PDType1Font.HELVETICA, metaSize, metadataLine(card), x0, cursor - metaSize);
        cursor -= metaSize + 10f;

        float instrSize = 8.5f;
        for (String line : wrap(instruction(card), PDType1Font.HELVETICA, instrSize, contentWidth)) {
            drawText(cs, PDType1Font.HELVETICA, instrSize, line, x0, cursor - instrSize);
            cursor -= instrSize + 3f;
        }
        cursor -= 8f;

        drawGrid(cs, card, x0, contentWidth, cursor);
    }

    private float drawGrid(PDPageContentStream cs, CotpCard card, float x0, float contentWidth, float top)
            throws IOException {
        CotpScheme scheme = card.scheme();
        List<Character> columns = scheme.columns();
        List<Integer> rows = scheme.rows();
        List<CellPosition> positions = scheme.positions();
        boolean paired = positions.size() > 1;

        float columnWidth = (contentWidth - ROW_HEADER_WIDTH) / columns.size();
        float lineHeight = paired ? PAIR_LINE_HEIGHT : CELL_LINE_HEIGHT;
        float cellHeight = lineHeight * positions.size();

        cs.setLineWidth(0.6f);
        cs.setStrokingColor(Color.GRAY);

        float cursor = top;
        float headerBottom = cursor - HEADER_HEIGHT;
        strokeRect(cs, x0, headerBottom, ROW_HEADER_WIDTH, HEADER_HEIGHT);
        for (int c = 0; c < columns.size(); c++) {
            float cx = x0 + ROW_HEADER_WIDTH + c * columnWidth;
            strokeRect(cs, cx, headerBottom, columnWidth, HEADER_HEIGHT);
            drawCentered(cs, PDType1Font.HELVETICA_BOLD, HEADER_FONT_SIZE,
                    String.valueOf(columns.get(c)), cx + columnWidth / 2f, headerBottom + HEADER_HEIGHT / 2f);
        }
        cursor = headerBottom;

        for (int row : rows) {
            float rowBottom = cursor - cellHeight;
            strokeRect(cs, x0, rowBottom, ROW_HEADER_WIDTH, cellHeight);
            drawCentered(cs, PDType1Font.HELVETICA_BOLD, HEADER_FONT_SIZE,
                    String.valueOf(row), x0 + ROW_HEADER_WIDTH / 2f, rowBottom + cellHeight / 2f);

            for (int c = 0; c < columns.size(); c++) {
                char column = columns.get(c);
                float cx = x0 + ROW_HEADER_WIDTH + c * columnWidth;
                strokeRect(cs, cx, rowBottom, columnWidth, cellHeight);
                for (int p = 0; p < positions.size(); p++) {
                    String word = card.grid().get(new Cell(column, row, positions.get(p)).id());
                    if (word == null) {
                        continue;
                    }
                    float centerY = positions.size() == 1
                            ? rowBottom + cellHeight / 2f
                            : rowBottom + cellHeight * (positions.size() - p - 0.5f) / positions.size();
                    drawCentered(cs, PDType1Font.HELVETICA, WORD_FONT_SIZE, word,
                            cx + columnWidth / 2f, centerY);
                }
            }
            cursor = rowBottom;
        }
        return cursor;
    }

    private void drawQr(PDDocument doc, PDPageContentStream cs, String payload, float x, float y, float size)
            throws IOException {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, options.qrSize(), options.qrSize());
        } catch (WriterException e) {
            throw new CotpPdfException("Failed to generate the QR code for the card id", e);
        }
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        PDImageXObject qr = LosslessFactory.createFromImage(doc, image);
        cs.drawImage(qr, x, y, size, size);
    }

    private static String metadataLine(CotpCard card) {
        return "Version: " + card.scheme().version()
                + "    Generated: " + formatDate(card.createdAt())
                + "    Expires: " + formatDate(card.expiresAt());
    }

    private static String formatDate(Instant instant) {
        return instant == null ? "n/a" : DATE_FORMAT.format(instant);
    }

    private static String instruction(CotpCard card) {
        if (card.scheme().positions().size() > 1) {
            return "Find the cell named in your prompt, read the TOP (upper) or BOTTOM (lower) word, "
                    + "then enter that word together with your PIN.";
        }
        return "Find the cell named in your prompt, read its word, "
                + "then enter that word together with your PIN.";
    }

    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sanitize(text).split("\\s+")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            float width = font.getStringWidth(candidate) / 1000f * size;
            if (width > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static void strokeRect(PDPageContentStream cs, float x, float y, float width, float height)
            throws IOException {
        cs.addRect(x, y, width, height);
        cs.stroke();
    }

    private static void drawCentered(PDPageContentStream cs, PDFont font, float size, String text,
                                     float centerX, float centerY) throws IOException {
        String safe = sanitize(text);
        float width = font.getStringWidth(safe) / 1000f * size;
        drawText(cs, font, size, safe, centerX - width / 2f, centerY - size * 0.36f);
    }

    private static void drawText(PDPageContentStream cs, PDFont font, float size, String text,
                                 float x, float baselineY) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, baselineY);
        cs.showText(sanitize(text));
        cs.endText();
    }

    /**
     * Maps characters not representable by the standard-14 WinAnsi fonts to {@code '?'} (and
     * collapses line breaks to spaces) so rendering never fails on arbitrary card ids / titles /
     * custom dictionaries. Localised (non-Latin) output would require an embedded Unicode font.
     */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
