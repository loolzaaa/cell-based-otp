package com.cotp;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 Base32 codec (alphabet {@code A-Z2-7}), used to transport the COTP card secret
 * as a human-copyable string (for example for backup or provisioning), in the same style as
 * the {@code secret} parameter of an {@code otpauth://} URI.
 *
 * <p>Decoding accepts both padded and unpadded input and is case-insensitive.
 * Encoding produces unpadded output by default, matching common authenticator conventions.</p>
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] LOOKUP = new int[128];

    static {
        for (int i = 0; i < LOOKUP.length; i++) {
            LOOKUP[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            LOOKUP[ALPHABET.charAt(i)] = i;
            LOOKUP[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
    }

    private Base32() {
    }

    /**
     * Encodes bytes to unpadded Base32.
     *
     * @param data input bytes
     * @return Base32 string without padding
     */
    public static String encode(byte[] data) {
        return encode(data, false);
    }

    /**
     * Encodes bytes to Base32.
     *
     * @param data    input bytes
     * @param padding whether to append {@code '='} padding to a multiple of 8 characters
     * @return Base32 string
     */
    public static String encode(byte[] data, boolean padding) {
        if (data == null) {
            return "";
        }
        if (data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        if (padding) {
            while (sb.length() % 8 != 0) {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    /**
     * Decodes a Base32 string (padded or unpadded, case-insensitive).
     *
     * @param encoded input string
     * @return decoded bytes
     * @throws IllegalArgumentException if the input contains an illegal character
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Base32 input must not be null");
        }
        String s = encoded.trim().replace(" ", "");

        // Padding, when present, may only appear as a trailing run and the padded length must be
        // a multiple of 8 (RFC 4648 section 6).
        int eq = s.indexOf('=');
        if (eq == 0) {
            throw new IllegalArgumentException("Base32 input cannot consist only of padding: " + encoded);
        }
        if (eq > 0) {
            for (int i = eq; i < s.length(); i++) {
                if (s.charAt(i) != '=') {
                    throw new IllegalArgumentException("Illegal Base32 padding in " + encoded);
                }
            }
            if (s.length() % 8 != 0) {
                throw new IllegalArgumentException("Padded Base32 length must be a multiple of 8: " + encoded);
            }
            s = s.substring(0, eq);
        }
        if (s.isEmpty()) {
            return new byte[0];
        }
        int remainder = s.length() % 8;
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            throw new IllegalArgumentException("Invalid Base32 length: " + encoded);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 5 / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int value = (c < 128) ? LOOKUP[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Illegal Base32 character at position " + i + ": '" + c + "' in " + encoded.toUpperCase(Locale.ROOT));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        // Unused trailing bits must be zero (canonical encoding).
        if (bitsLeft > 0 && (buffer & ((1 << bitsLeft) - 1)) != 0) {
            throw new IllegalArgumentException("Non-canonical Base32 (non-zero trailing bits): " + encoded);
        }
        return out.toByteArray();
    }
}
