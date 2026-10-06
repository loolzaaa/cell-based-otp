package ru.loolzaaa;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Generates and deterministically re-derives COTP cards.
 *
 * <p>Generation is a pure function {@code (secret, cardId, scheme) -> grid}. Consequently,
 * {@link #derive(CotpCard)} reproduces the exact same card from the persisted secret, which is
 * what allows the server to store only the secret (plus card id and scheme version) rather than the
 * printed words.</p>
 */
public final class CotpCardGenerator {

    private final CotpScheme scheme;

    /**
     * @param scheme card scheme; all cards produced by this generator share it
     */
    public CotpCardGenerator(CotpScheme scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
    }

    /** @return the scheme used by this generator. */
    public CotpScheme scheme() {
        return scheme;
    }

    /**
     * Generates a new card with a fresh CSPRNG secret.
     *
     * @param cardId public card identifier
     * @return a freshly generated card
     */
    public CotpCard generate(String cardId) {
        return generate(cardId, CotpSecret.generate(scheme.secretBytes()));
    }

    /**
     * Generates a card from an explicit secret.
     *
     * @param cardId public card identifier
     * @param secret card secret
     * @return the generated card
     */
    public CotpCard generate(String cardId, CotpSecret secret) {
        return generate(cardId, secret, null, null);
    }

    /**
     * Generates a card from an explicit secret with validity metadata.
     *
     * @param cardId    public card identifier
     * @param secret    card secret
     * @param createdAt creation timestamp, or {@code null}
     * @param expiresAt expiry timestamp, or {@code null}
     * @return the generated card
     */
    public CotpCard generate(String cardId, CotpSecret secret, Instant createdAt, Instant expiresAt) {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(secret, "secret");
        byte[] gridKey = CotpDerivation.gridKey(secret.copyBytes(), cardId, scheme);
        Map<String, String> grid = CotpDerivation.buildGrid(gridKey, scheme);
        return new CotpCard(cardId, secret, scheme, grid, createdAt, expiresAt);
    }

    /**
     * Re-derives the grid of an existing card from its secret (for example after loading the
     * secret from the database) using the card's own scheme, and verifies that the result matches
     * the card's cached grid. A mismatch indicates a corrupted card, a wrong secret, or an
     * inconsistent scheme and is reported as a {@link CotpException}.
     *
     * @param card card whose grid must be regenerated
     * @return a card with a freshly derived grid
     * @throws CotpException if the re-derived grid does not match the cached grid
     */
    public CotpCard derive(CotpCard card) {
        Objects.requireNonNull(card, "card");
        CotpCard regenerated = new CotpCardGenerator(card.scheme())
                .generate(card.cardId(), card.secret(), card.createdAt(), card.expiresAt());
        if (!regenerated.grid().equals(card.grid())) {
            throw new CotpException("Re-derived grid does not match the cached grid for card " + card.cardId());
        }
        return regenerated;
    }

    /**
     * Returns the secret word for a single cell, recomputing the grid on demand.
     * Helper for verification paths that persist only the secret.
     *
     * @param cardId card identifier
     * @param secret card secret
     * @param cell   requested cell
     * @return the word printed at the cell
     */
    public String wordFor(String cardId, CotpSecret secret, Cell cell) {
        return deriveGridOnly(cardId, secret).get(cell.id());
    }

    private Map<String, String> deriveGridOnly(String cardId, CotpSecret secret) {
        byte[] gridKey = CotpDerivation.gridKey(secret.copyBytes(), cardId, scheme);
        return CotpDerivation.buildGrid(gridKey, scheme);
    }
}
