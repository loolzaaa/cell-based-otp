package com.cotp;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic core of COTP: derivation of the grid key, the per-cell word index and the
 * complete word grid from a card secret.
 *
 * <p>This class contains no mutable shared state; every method is a pure function of its
 * arguments, which is the foundation of the determinism guarantee described in the COTP
 * specification.</p>
 *
 * <h2>Algorithm (COTP v1)</h2>
 * <ol>
 *   <li>{@code PRK = HKDF-Extract(salt = UTF8(cardId), IKM = cardSecret)} &mdash; RFC 5869.</li>
 *   <li>{@code K_grid = HKDF-Expand(PRK, info = UTF8(version + "/grid"), 32)}.</li>
 *   <li>For a slot {@code s} (canonical id, e.g. {@code "C2"}) and nonce {@code n}:
 *       <pre>
 *       label = version + "/word|" + s + "|" + n
 *       h     = HMAC-SHA-256(K_grid, UTF8(label))
 *       index = ((h[0] &amp; 0x07) &lt;&lt; 8) | (h[1] &amp; 0xFF)      // exactly 11 bits -&gt; 0..2047</pre></li>
 *   <li>The grid is assembled slot-by-slot in canonical order; on a duplicate word the nonce is
 *       incremented until an unused word is produced (deterministic collision resolution).</li>
 * </ol>
 *
 * <p>Because the dictionary size is a power of two (2048 = 2<sup>11</sup>), step 3 is free of
 * modulo bias: the 11 extracted bits form a uniformly distributed index over the whole
 * dictionary.</p>
 */
final class CotpDerivation {

    private static final int MAX_COLLISION_NONCE = 1_000_000;

    private CotpDerivation() {
    }

    /**
     * Derives the grid key {@code K_grid} from a card secret.
     *
     * @param secret raw card secret
     * @param cardId public card identifier, used as HKDF salt
     * @param scheme card scheme
     * @return 32-byte grid key
     */
    static byte[] gridKey(byte[] secret, String cardId, CotpScheme scheme) {
        return Hkdf.derive(secret, utf8(cardId), scheme.gridInfo(), 32);
    }

    /**
     * Derives the challenge key {@code K_challenge} (used only in time-based mode).
     *
     * @param secret raw card secret
     * @param cardId public card identifier, used as HKDF salt
     * @param scheme card scheme
     * @return 32-byte challenge key
     */
    static byte[] challengeKey(byte[] secret, String cardId, CotpScheme scheme) {
        return Hkdf.derive(secret, utf8(cardId), scheme.challengeInfo(), 32);
    }

    /**
     * Computes the dictionary index for a single grid slot and nonce.
     *
     * @param gridKey 32-byte grid key
     * @param cell    target cell
     * @param nonce   collision-resolution nonce (0 for the first attempt)
     * @param scheme  card scheme
     * @return index in {@code [0, dictionary.size())}
     */
    static int wordIndex(byte[] gridKey, Cell cell, int nonce, CotpScheme scheme) {
        String label = scheme.wordLabelPrefix() + cell.id() + "|" + nonce;
        byte[] h = Hmac.sha256(gridKey, utf8(label));
        // Take 16 uniform bits from the HMAC output and mask to the dictionary's bit width.
        // The low b bits of a uniform 16-bit value are uniform, and the dictionary size is a
        // power of two (b <= 16), so this mapping is free of modulo bias. For the 11-bit
        // (2048-word) dictionary this is identical to ((h[0] & 0x07) << 8) | (h[1] & 0xFF).
        int value16 = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
        return value16 & ((1 << scheme.dictionary().bits()) - 1);
    }

    /**
     * Builds the complete word grid deterministically.
     *
     * @param gridKey 32-byte grid key
     * @param scheme  card scheme
     * @return ordered map {@code cellId -> word}
     */
    static Map<String, String> buildGrid(byte[] gridKey, CotpScheme scheme) {
        WordDictionary dictionary = scheme.dictionary();
        Map<String, String> grid = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Cell cell : scheme.cells()) {
            int nonce = 0;
            while (true) {
                String word = dictionary.wordAt(wordIndex(gridKey, cell, nonce, scheme));
                if (used.add(word)) {
                    grid.put(cell.id(), word);
                    break;
                }
                if (++nonce > MAX_COLLISION_NONCE) {
                    throw new CotpCryptoException("Unable to resolve unique word for cell " + cell
                            + " after " + MAX_COLLISION_NONCE + " attempts (dictionary too small?)");
                }
            }
        }
        return grid;
    }

    /**
     * Selects a grid slot index uniformly at random using rejection sampling.
     *
     * <p>Used for {@link ChallengeMode#TIME_BASED}; {@link ChallengeMode#RANDOM} relies on
     * {@link java.security.SecureRandom#nextInt(int)} which is already unbiased.</p>
     *
     * @param challengeKey 32-byte challenge key
     * @param timeStep     time step value {@code T = floor(epochSeconds / step)}
     * @param slotCount    number of slots
     * @param scheme       card scheme
     * @return index in {@code [0, slotCount)}
     */
    static int selectSlotIndex(byte[] challengeKey, long timeStep, int slotCount, CotpScheme scheme) {
        long range = 1L << 32;
        long limit = (range / slotCount) * slotCount; // largest multiple of slotCount <= 2^32
        int attempt = 0;
        while (true) {
            String label = scheme.challengeLabelPrefix() + timeStep + "|" + attempt;
            byte[] h = Hmac.sha256(challengeKey, utf8(label));
            long value = ((long) (h[0] & 0xFF) << 24)
                    | ((long) (h[1] & 0xFF) << 16)
                    | ((long) (h[2] & 0xFF) << 8)
                    | (h[3] & 0xFF);
            if (value < limit) {
                return (int) (value % slotCount);
            }
            if (++attempt > 1_000_000) {
                throw new CotpCryptoException("Rejection sampling failed for slot selection");
            }
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
