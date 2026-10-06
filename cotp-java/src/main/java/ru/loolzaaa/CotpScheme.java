package ru.loolzaaa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable <b>scheme</b> of a COTP card: everything that determines the printed grid and the
 * time-derived challenge cell — version, dictionary, layout and time-step geometry.
 *
 * <p>A scheme is part of the card's identity: the exact same scheme MUST be used when a card is
 * regenerated for verification. It intentionally contains no operational policy (burn policy, TTL,
 * throttling, PIN hashing); those live in {@link CotpPolicy}.</p>
 *
 * <p><b>Versioning.</b> The derivation labels are namespaced by {@link #version()}. Changing labels
 * or layout for an existing version would invalidate all issued cards, so it must be done under a
 * new version.</p>
 */
public final class CotpScheme {

    /** Default scheme version. Part of all derivation labels. */
    public static final String DEFAULT_VERSION = "COTP/v1";

    private static final char[] DEFAULT_COLUMNS = {'A', 'B', 'C', 'D', 'E'};
    private static final int[] DEFAULT_ROWS = {1, 2, 3, 4, 5};

    private final String version;
    private final WordDictionary dictionary;
    private final List<Character> columns;
    private final List<Integer> rows;
    private final List<CellPosition> positions;
    private final int secretBytes;
    private final int timeStepSeconds;
    private final int timeWindowBack;
    private final int timeWindowForward;

    private CotpScheme(Builder b) {
        this.version = b.version;
        this.dictionary = b.dictionary;
        this.columns = List.copyOf(b.columns);
        this.rows = List.copyOf(b.rows);
        this.positions = List.copyOf(b.positions);
        this.secretBytes = b.secretBytes;
        this.timeStepSeconds = b.timeStepSeconds;
        this.timeWindowBack = b.timeWindowBack;
        this.timeWindowForward = b.timeWindowForward;

        if (dictionary == null) {
            throw new CotpConfigurationException("dictionary must not be null");
        }
        if (columns.isEmpty() || rows.isEmpty() || positions.isEmpty()) {
            throw new CotpConfigurationException("columns, rows and positions must not be empty");
        }
        if (new java.util.HashSet<>(columns).size() != columns.size()) {
            throw new CotpConfigurationException("Duplicate columns are not allowed: " + columns);
        }
        if (new java.util.HashSet<>(rows).size() != rows.size()) {
            throw new CotpConfigurationException("Duplicate rows are not allowed: " + rows);
        }
        if (new java.util.HashSet<>(positions).size() != positions.size()) {
            throw new CotpConfigurationException("Duplicate positions are not allowed: " + positions);
        }
        for (char c : columns) {
            if (c < 'A' || c > 'Z') {
                throw new CotpConfigurationException("Column must be a letter A-Z, got '" + c + "'");
            }
        }
        for (int r : rows) {
            if (r < 1) {
                throw new CotpConfigurationException("Row must be >= 1, got " + r);
            }
        }
        long slots = (long) columns.size() * rows.size() * positions.size();
        if (slots > 4096) {
            throw new CotpConfigurationException("Grid is unreasonably large: " + slots + " slots");
        }
        if (slots > dictionary.size()) {
            throw new CotpConfigurationException("Grid has " + slots + " slots but dictionary has only "
                    + dictionary.size() + " words; unique words per grid cannot be guaranteed");
        }
        if (secretBytes < 16 || secretBytes > 64) {
            throw new CotpConfigurationException("secretBytes must be in [16, 64], got " + secretBytes);
        }
        if (timeStepSeconds < 1) {
            throw new CotpConfigurationException("timeStepSeconds must be >= 1");
        }
        if (timeWindowBack < 0 || timeWindowForward < 0) {
            throw new CotpConfigurationException("time windows must be >= 0");
        }
        if (timeWindowBack > 100_000 || timeWindowForward > 100_000) {
            throw new CotpConfigurationException("time windows are unreasonably large (max 100000)");
        }
        if (version == null || version.isBlank() || version.indexOf('|') >= 0
                || Validation.hasBlankOrFormatChar(version)) {
            throw new CotpConfigurationException("version must be non-blank and must not contain '|'");
        }
    }

    /** @return a 5&times;5 single-word scheme with all defaults and the BIP-39 dictionary. */
    public static CotpScheme defaults() {
        return builder().build();
    }

    /** @return a fresh builder pre-populated with defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public String version() {
        return version;
    }

    public WordDictionary dictionary() {
        return dictionary;
    }

    public List<Character> columns() {
        return columns;
    }

    public List<Integer> rows() {
        return rows;
    }

    public List<CellPosition> positions() {
        return positions;
    }

    public int secretBytes() {
        return secretBytes;
    }

    public int timeStepSeconds() {
        return timeStepSeconds;
    }

    public int timeWindowBack() {
        return timeWindowBack;
    }

    public int timeWindowForward() {
        return timeWindowForward;
    }

    /**
     * @return the canonical, deterministic order of all grid slots (columns outer, then rows, then
     *         positions). Used both when assembling the grid and when resolving duplicate-word
     *         collisions; part of the deterministic contract.
     */
    public List<Cell> cells() {
        List<Cell> cells = new ArrayList<>(columns.size() * rows.size() * positions.size());
        for (char column : columns) {
            for (int row : rows) {
                for (CellPosition position : positions) {
                    cells.add(new Cell(column, row, position));
                }
            }
        }
        return List.copyOf(cells);
    }

    /** @return total number of word slots on the card. */
    public int slotCount() {
        return columns.size() * rows.size() * positions.size();
    }

    /** @return HKDF {@code info} used to derive the grid key {@code K_grid}. */
    public byte[] gridInfo() {
        return (version + "/grid").getBytes(StandardCharsets.UTF_8);
    }

    /** @return HKDF {@code info} used to derive the challenge key {@code K_challenge}. */
    public byte[] challengeInfo() {
        return (version + "/challenge").getBytes(StandardCharsets.UTF_8);
    }

    /** @return prefix of the canonical word-derivation label. */
    public String wordLabelPrefix() {
        return version + "/word|";
    }

    /** @return prefix of the canonical challenge-derivation label. */
    public String challengeLabelPrefix() {
        return version + "/challenge|";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CotpScheme other)) {
            return false;
        }
        return secretBytes == other.secretBytes
                && timeStepSeconds == other.timeStepSeconds
                && timeWindowBack == other.timeWindowBack
                && timeWindowForward == other.timeWindowForward
                && version.equals(other.version)
                && columns.equals(other.columns)
                && rows.equals(other.rows)
                && positions.equals(other.positions)
                && dictionary.words().equals(other.dictionary.words());
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, dictionary.words(), columns, rows, positions,
                secretBytes, timeStepSeconds, timeWindowBack, timeWindowForward);
    }

    @Override
    public String toString() {
        return "CotpScheme{" + version + ", columns=" + columns.size() + ", rows=" + rows.size()
                + ", positions=" + positions + ", slots=" + slotCount()
                + ", dictionary=" + dictionary.size() + "}";
    }

    /** Mutable builder for {@link CotpScheme}. */
    public static final class Builder {
        private String version = DEFAULT_VERSION;
        private WordDictionary dictionary;
        private final List<Character> columns = new ArrayList<>();
        private final List<Integer> rows = new ArrayList<>();
        private final List<CellPosition> positions = new ArrayList<>();
        private int secretBytes = 32;
        private int timeStepSeconds = 30;
        private int timeWindowBack = 4;
        private int timeWindowForward = 1;

        private Builder() {
            for (char c : DEFAULT_COLUMNS) {
                columns.add(c);
            }
            for (int r : DEFAULT_ROWS) {
                rows.add(r);
            }
            positions.add(CellPosition.SINGLE);
        }

        /** Overrides the scheme version (changes all derivation labels). */
        public Builder version(String version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        /** Overrides the word dictionary. */
        public Builder dictionary(WordDictionary dictionary) {
            this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
            return this;
        }

        /** Replaces the column list. */
        public Builder columns(List<Character> columns) {
            Objects.requireNonNull(columns, "columns");
            this.columns.clear();
            columns.forEach(c -> this.columns.add(Character.toUpperCase(c)));
            return this;
        }

        /** Replaces the row list. */
        public Builder rows(List<Integer> rows) {
            Objects.requireNonNull(rows, "rows");
            this.rows.clear();
            this.rows.addAll(rows);
            return this;
        }

        /** Replaces the position list (use {@code TOP, BOTTOM} for the Avatier word-pair layout). */
        public Builder positions(List<CellPosition> positions) {
            Objects.requireNonNull(positions, "positions");
            this.positions.clear();
            this.positions.addAll(positions);
            return this;
        }

        /** Secret length in bytes (default 32 = 256 bits; minimum 16 = 128 bits). */
        public Builder secretBytes(int secretBytes) {
            this.secretBytes = secretBytes;
            return this;
        }

        /** Time step in seconds for time-based challenges (default 30 s). */
        public Builder timeStepSeconds(int timeStepSeconds) {
            this.timeStepSeconds = timeStepSeconds;
            return this;
        }

        /** Allowed number of previous time steps when validating a time-based challenge. */
        public Builder timeWindowBack(int timeWindowBack) {
            this.timeWindowBack = timeWindowBack;
            return this;
        }

        /** Allowed number of future time steps when validating a time-based challenge. */
        public Builder timeWindowForward(int timeWindowForward) {
            this.timeWindowForward = timeWindowForward;
            return this;
        }

        /** @return an immutable scheme. */
        public CotpScheme build() {
            if (dictionary == null) {
                dictionary = WordDictionary.bip39English();
            }
            return new CotpScheme(this);
        }
    }
}
