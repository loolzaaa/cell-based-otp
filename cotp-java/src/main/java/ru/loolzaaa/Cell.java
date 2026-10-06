package ru.loolzaaa;

import java.util.Locale;
import java.util.Objects;

/**
 * An immutable address of a single secret word inside a COTP challenge card.
 *
 * <p>A cell is defined by a column letter (for example {@code A}..{@code E}), a row number
 * (for example {@code 1}..{@code 5}) and an optional {@link CellPosition}. For the default
 * single-word layout the position is {@link CellPosition#SINGLE}; for the Avatier-compatible
 * word-pair layout it is {@link CellPosition#TOP} or {@link CellPosition#BOTTOM}.</p>
 *
 * <p>The {@link #id()} value is the canonical textual address used in the derivation label
 * (for example {@code "C2"} for a single-word cell, {@code "C2T"} for the top word of a pair).
 * It is part of the cryptographic contract and is stable across versions.</p>
 *
 * @param column   column letter, stored upper-case
 * @param row      row number, {@code >= 1}
 * @param position vertical position inside the cell, never {@code null}
 */
public record Cell(char column, int row, CellPosition position) {

    public Cell {
        column = Character.toUpperCase(column);
        if (column < 'A' || column > 'Z') {
            throw new IllegalArgumentException("Cell column must be a letter A-Z, got '" + column + "'");
        }
        if (row < 1) {
            throw new IllegalArgumentException("Cell row must be >= 1, got " + row);
        }
        Objects.requireNonNull(position, "position");
    }

    /**
     * Convenience factory for a single-word cell.
     *
     * @param column column letter
     * @param row    row number
     * @return a cell with {@link CellPosition#SINGLE}
     */
    public static Cell of(char column, int row) {
        return new Cell(column, row, CellPosition.SINGLE);
    }

    /**
     * @return canonical textual address used in the derivation label, e.g. {@code "C2"} or {@code "C2T"}.
     */
    public String id() {
        if (position == CellPosition.SINGLE) {
            return "" + column + row;
        }
        return "" + column + row + position.token();
    }

    /**
     * Parses a canonical cell address.
     *
     * @param id address such as {@code "C2"} or {@code "C2T"} (case-insensitive)
     * @return parsed cell
     * @throws IllegalArgumentException if the address is malformed
     */
    public static Cell parse(String id) {
        Objects.requireNonNull(id, "id");
        String s = id.trim().toUpperCase(Locale.ROOT);
        if (s.length() < 2) {
            throw new IllegalArgumentException("Malformed cell id: '" + id + "'");
        }
        char column = s.charAt(0);
        if (column < 'A' || column > 'Z') {
            throw new IllegalArgumentException("Malformed cell id (bad column): '" + id + "'");
        }
        int end = 1;
        while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
            end++;
        }
        if (end == 1) {
            throw new IllegalArgumentException("Malformed cell id (missing row): '" + id + "'");
        }
        int row = Integer.parseInt(s.substring(1, end));
        CellPosition position = CellPosition.SINGLE;
        if (end < s.length()) {
            if (end != s.length() - 1) {
                throw new IllegalArgumentException("Malformed cell id (trailing characters): '" + id + "'");
            }
            CellPosition parsed = CellPosition.fromToken(s.charAt(end));
            if (parsed == CellPosition.SINGLE) {
                // Single-word cells have no positional token; "A1S" is a non-canonical alias of "A1".
                throw new IllegalArgumentException("Non-canonical cell id (explicit SINGLE token): '" + id + "'");
            }
            position = parsed;
        }
        return new Cell(column, row, position);
    }

    @Override
    public String toString() {
        return id();
    }
}
