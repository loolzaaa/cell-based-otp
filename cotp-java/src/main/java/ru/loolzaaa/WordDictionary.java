package ru.loolzaaa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable COTP word list.
 *
 * <p>The dictionary size MUST be a power of two. This is a fundamental property of the
 * COTP design: the word-index function extracts exactly {@code log2(size)} bits from an
 * HMAC output, so a power-of-two dictionary maps those bits to an index <em>without any
 * modulo bias</em>. The bundled {@link #bip39English()} dictionary has 2048 = 2<sup>11</sup>
 * words, i.e. 11 bits per word.</p>
 *
 * <p>Dictionaries are immutable and shareable between cards; only the card secret (and card id)
 * is secret. The same dictionary MUST be used for card generation and verification.</p>
 */
public final class WordDictionary {

    /** Resource path of the bundled BIP-39 English dictionary. */
    public static final String BIP39_ENGLISH_RESOURCE = "/ru/loolzaaa/cotp-english.txt";

    private static final int MIN_SIZE = 256;

    private final List<String> words;
    private final Map<String, Integer> indexByWord;
    private final int bits;

    private WordDictionary(List<String> words) {
        this.words = List.copyOf(words);
        Map<String, Integer> map = new HashMap<>(words.size() * 2);
        for (int i = 0; i < words.size(); i++) {
            map.put(words.get(i), i);
        }
        this.indexByWord = Map.copyOf(map);
        this.bits = Integer.numberOfTrailingZeros(words.size());
    }

    /**
     * Builds a dictionary from an explicit word list.
     *
     * @param words words in canonical order; must be unique and the count must be a power of two
     * @return immutable dictionary
     * @throws IllegalArgumentException if the list is null/too small/not a power of two/contains duplicates
     */
    public static WordDictionary of(List<String> words) {
        Objects.requireNonNull(words, "words");
        if (words.size() < MIN_SIZE) {
            throw new CotpConfigurationException("Dictionary must contain at least " + MIN_SIZE + " words, got " + words.size());
        }
        if ((words.size() & (words.size() - 1)) != 0) {
            throw new CotpConfigurationException("Dictionary size must be a power of two, got " + words.size());
        }
        if (words.size() > 65_536) {
            throw new CotpConfigurationException("Dictionary size must be <= 65536 (16-bit index), got " + words.size());
        }
        List<String> copy = new ArrayList<>(words.size());
        java.util.Set<String> normalized = new java.util.HashSet<>(words.size() * 2);
        for (String w : words) {
            if (w == null || w.isBlank()) {
                throw new CotpConfigurationException("Dictionary contains a blank word");
            }
            String word = w.trim();
            if (Validation.hasBlankOrFormatChar(word)) {
                throw new CotpConfigurationException("Dictionary word must not contain whitespace/format characters: '" + word + "'");
            }
            // Words are compared case-insensitively during authentication, therefore case-only
            // variants would be ambiguous and MUST be rejected as duplicates.
            if (!normalized.add(word.toLowerCase(java.util.Locale.ROOT))) {
                throw new CotpConfigurationException("Dictionary contains duplicate (case-insensitive) word: '" + word + "'");
            }
            copy.add(word);
        }
        return new WordDictionary(copy);
    }

    /**
     * Loads a dictionary from any text input, one word per line.
     *
     * @param in text input (UTF-8); blank lines are ignored
     * @return immutable dictionary
     */
    public static WordDictionary load(InputStream in) {
        Objects.requireNonNull(in, "in");
        List<String> words = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.trim();
                if (!w.isEmpty()) {
                    words.add(w);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read dictionary", e);
        }
        return of(words);
    }

    /**
     * Loads the bundled BIP-39 English dictionary (2048 words, 11 bits/word).
     *
     * @return the standard 2048-word dictionary
     */
    public static WordDictionary bip39English() {
        try (InputStream in = WordDictionary.class.getResourceAsStream(BIP39_ENGLISH_RESOURCE)) {
            if (in == null) {
                throw new CotpException("Bundled dictionary not found on classpath: " + BIP39_ENGLISH_RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load bundled dictionary", e);
        }
    }

    /** @return number of words in the dictionary (a power of two). */
    public int size() {
        return words.size();
    }

    /** @return number of bits required to represent a word index ({@code log2(size)}). */
    public int bits() {
        return bits;
    }

    /**
     * @param index zero-based index
     * @return the word at {@code index}
     */
    public String wordAt(int index) {
        return words.get(index);
    }

    /**
     * @param word a word from the dictionary
     * @return zero-based index of {@code word}
     * @throws IllegalArgumentException if the word is not present
     */
    public int indexOf(String word) {
        Integer i = indexByWord.get(word);
        if (i == null) {
            throw new IllegalArgumentException("Word not in dictionary: '" + word + "'");
        }
        return i;
    }

    /** @return {@code true} if the word belongs to this dictionary. */
    public boolean contains(String word) {
        return word != null && indexByWord.containsKey(word);
    }

    /** @return an immutable, canonical-order view of all words. */
    public List<String> words() {
        return words;
    }
}
