package com.cotp;

import java.util.List;

/**
 * Renders a {@link CotpCard} as a plain-text table, for demos, tests and as a starting point for the
 * printable PDF template.
 *
 * <p>Handles both the single-word and the TOP/BOTTOM word-pair layouts.</p>
 */
public final class CotpCardPrinter {

    private CotpCardPrinter() {
    }

    /**
     * @param card card to render
     * @return multi-line textual representation of the grid
     */
    public static String render(CotpCard card) {
        CotpScheme scheme = card.scheme();
        List<Character> columns = scheme.columns();
        List<Integer> rows = scheme.rows();
        boolean paired = scheme.positions().size() > 1;
        int cellWidth = 12;

        StringBuilder sb = new StringBuilder();
        sb.append("Card ID: ").append(card.cardId())
                .append("   (version ").append(scheme.version()).append(")\n");

        sb.append(pad("", cellWidth + 2));
        for (char c : columns) {
            sb.append(pad(String.valueOf(c), cellWidth + 2));
        }
        sb.append('\n');

        for (int row : rows) {
            for (CellPosition position : scheme.positions()) {
                StringBuilder line = new StringBuilder();
                line.append(pad((position == scheme.positions().get(0) ? String.valueOf(row) : ""), cellWidth + 2));
                for (char c : columns) {
                    String word = card.grid().get(new Cell(c, row, position).id());
                    line.append(pad(word == null ? "" : word, cellWidth + 2));
                }
                sb.append(line).append('\n');
            }
            if (!paired) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }
}
