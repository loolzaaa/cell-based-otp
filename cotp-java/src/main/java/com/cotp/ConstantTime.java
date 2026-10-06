package com.cotp;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Constant-time comparison helpers, used to compare secret words and PIN material without
 * leaking information through timing side channels.
 *
 * <p>Length differences are compared directly (a length is not itself comparison-dependent),
 * but the byte/char loop never short-circuits, so for equal-length inputs the comparison time
 * is independent of where the first mismatch occurs.</p>
 */
public final class ConstantTime {

    private ConstantTime() {
    }

    /**
     * Constant-time equality for byte arrays.
     *
     * @param a first array (may be {@code null})
     * @param b second array (may be {@code null})
     * @return {@code true} if both are byte-wise equal
     */
    public static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Constant-time equality for char arrays.
     *
     * @param a first array (may be {@code null})
     * @param b second array (may be {@code null})
     * @return {@code true} if both are char-wise equal
     */
    public static boolean equals(char[] a, char[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Constant-time comparison of two secret words, ignoring surrounding whitespace and case.
     *
     * @param candidate user-provided answer
     * @param expected  expected word from the card
     * @return {@code true} if the normalised values match
     */
    public static boolean wordsMatch(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return candidate == expected;
        }
        char[] a = normalise(candidate);
        char[] b = normalise(expected);
        boolean equal = equals(a, b);
        java.util.Arrays.fill(a, '\0');
        java.util.Arrays.fill(b, '\0');
        return equal;
    }

    private static char[] normalise(String s) {
        return s.trim().toLowerCase(Locale.ROOT).toCharArray();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
