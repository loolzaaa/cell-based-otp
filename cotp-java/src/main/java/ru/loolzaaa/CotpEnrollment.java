package ru.loolzaaa;

import java.util.Objects;

/**
 * Result of enrolling a card: the generated {@link #card()}, the stored {@link #pinHash()} and the
 * persistable {@link #credential()} (which contains the secret — store it encrypted at rest).
 *
 * @param card       the generated card (its {@link CotpCard#grid() grid} is what gets printed)
 * @param pinHash    encoded PIN hash to persist for verification
 * @param credential the server-side credential (card id + secret + scheme version)
 */
public record CotpEnrollment(CotpCard card, String pinHash, CotpCredential credential) {

    public CotpEnrollment {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(pinHash, "pinHash");
        Objects.requireNonNull(credential, "credential");
    }

    /**
     * @return the encoded credential (contains the secret in the clear; seal it with a
     *         {@link SecretCipher} before persisting)
     */
    public String credentialEncoded() {
        return CotpCredentialCodec.encode(credential);
    }
}
