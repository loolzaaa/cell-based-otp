package ru.loolzaaa;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A generated COTP challenge card: a fixed grid of secret words plus the secret that
 * deterministically regenerates it.
 *
 * <p>The {@link #grid()} is a derived, cacheable view. The authoritative state that must be
 * persisted on the server is only {@link #cardId()} together with {@link #secret()} (and the
 * {@link #scheme()} version). The grid can always be recomputed with
 * {@link CotpCardGenerator#derive(CotpCard)}.</p>
 */
public final class CotpCard {

    private final String cardId;
    private final CotpSecret secret;
    private final CotpScheme scheme;
    private final Map<String, String> grid;
    private final Instant createdAt;
    private final Instant expiresAt;

    CotpCard(String cardId, CotpSecret secret, CotpScheme scheme, Map<String, String> grid,
             Instant createdAt, Instant expiresAt) {
        this.cardId = Objects.requireNonNull(cardId, "cardId");
        this.secret = Objects.requireNonNull(secret, "secret");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        // Preserve canonical slot order (Map.copyOf would drop insertion order and reorder
        // iteration across JVM runs, breaking the determinism contract).
        this.grid = Collections.unmodifiableMap(new LinkedHashMap<>(grid));
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** @return public card identifier (safe to print on the card; used as HKDF salt). */
    public String cardId() {
        return cardId;
    }

    /** @return the card secret (sensitive; store encrypted at rest). */
    public CotpSecret secret() {
        return secret;
    }

    /** @return the scheme (layout/version/dictionary) this card was created with. */
    public CotpScheme scheme() {
        return scheme;
    }

    /** @return an immutable map {@code cellId -> word} in canonical slot order. */
    public Map<String, String> grid() {
        return grid;
    }

    /** @return creation timestamp, or {@code null} if not set. */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return expiry timestamp, or {@code null} if the card does not expire. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** @return total number of word slots. */
    public int slotCount() {
        return grid.size();
    }

    /**
     * @param cell grid cell
     * @return the secret word printed at {@code cell}
     * @throws IllegalArgumentException if the cell does not belong to this card's layout
     */
    public String wordAt(Cell cell) {
        String word = grid.get(cell.id());
        if (word == null) {
            throw new IllegalArgumentException("Cell " + cell.id() + " is not part of this card");
        }
        return word;
    }

    /**
     * @param cellId canonical cell id, e.g. {@code "C2"} or {@code "C2T"}
     * @return the secret word printed at the cell
     * @throws IllegalArgumentException if the cell does not belong to this card's layout
     */
    public String wordAt(String cellId) {
        return wordAt(Cell.parse(cellId));
    }

    @Override
    public String toString() {
        return "CotpCard{" + cardId + ", slots=" + grid.size() + ", version=" + scheme.version() + "}";
    }
}
