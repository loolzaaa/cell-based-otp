package ru.loolzaaa;

/**
 * Thrown by {@link CotpChallengeGenerator} when a card under {@link BurnPolicy#CELL} has no
 * unused cells left and therefore can no longer issue a challenge. The card must be
 * re-enrolled.
 */
public class CardExhaustedException extends CotpException {

    private static final long serialVersionUID = 1L;

    private final String cardId;

    public CardExhaustedException(String cardId) {
        this(cardId, "all cells are burned");
    }

    /**
     * @param cardId the exhausted card
     * @param reason human-readable reason (for example "all cells are burned" or
     *               "all cells are within the cooldown window")
     */
    public CardExhaustedException(String cardId, String reason) {
        super("Card " + cardId + " has no available cells: " + reason);
        this.cardId = cardId;
    }

    /** @return the identifier of the exhausted card. */
    public String cardId() {
        return cardId;
    }
}
