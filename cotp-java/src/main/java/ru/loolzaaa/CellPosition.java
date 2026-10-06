package ru.loolzaaa;

/**
 * Vertical position of the secret word inside a grid cell.
 *
 * <p>COTP supports two card layouts:</p>
 * <ul>
 *   <li>{@link #SINGLE} — one word per cell (default COTP layout, 5&times;5 = 25 slots);</li>
 *   <li>{@link #TOP} / {@link #BOTTOM} — two words per cell (Avatier Identity Challenge Card
 *       compatible layout, 5&times;5&times;2 = 50 slots).</li>
 * </ul>
 *
 * <p>The {@link #token()} value is part of the canonical derivation label, therefore it is
 * part of the wire/spec contract and MUST NOT change for a given COTP version.</p>
 */
public enum CellPosition {

    /** Single word per cell (default). */
    SINGLE('S'),

    /** Upper word of a two-word cell. */
    TOP('T'),

    /** Lower word of a two-word cell. */
    BOTTOM('B');

    private final char token;

    CellPosition(char token) {
        this.token = token;
    }

    /**
     * @return one-character canonical token embedded into the word-derivation label
     *         ({@code S}, {@code T} or {@code B}).
     */
    public char token() {
        return token;
    }

    /**
     * Resolves a position from its canonical token.
     *
     * @param token one of {@code 'S'}, {@code 'T'}, {@code 'B'} (case-insensitive)
     * @return matching position
     * @throws IllegalArgumentException if the token is not recognised
     */
    public static CellPosition fromToken(char token) {
        char c = Character.toUpperCase(token);
        for (CellPosition p : values()) {
            if (p.token == c) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown cell position token: '" + token + "'");
    }
}
