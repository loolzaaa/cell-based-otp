package ru.loolzaaa;

/**
 * Small internal validation helpers shared by configuration and dictionary parsing.
 */
final class Validation {

    private Validation() {
    }

    /**
     * Returns {@code true} if the string contains a blank-like or format character that would
     * make it unsuitable as a single token (word or version label).
     *
     * <p>Besides {@link Character#isWhitespace(int)} this also rejects space separators
     * (for example NBSP {@code U+00A0}) and Unicode format characters (for example the zero-width
     * space {@code U+200B} and the BOM {@code U+FEFF}).</p>
     */
    static boolean hasBlankOrFormatChar(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isWhitespace(cp)
                    || Character.isSpaceChar(cp)
                    || Character.getType(cp) == Character.FORMAT) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
