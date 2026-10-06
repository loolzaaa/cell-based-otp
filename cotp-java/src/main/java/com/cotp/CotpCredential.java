package com.cotp;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable server-side credential of a COTP card: the only secret material that must persist.
 *
 * <p>A credential bundles the public {@link #cardId()}, the {@link #secret()}, the scheme version
 * ({@link #schemeId()}) and optional validity metadata. The printed grid is never stored — it is
 * regenerated deterministically via {@link #toCard(CotpScheme)} / {@link #toCard(SchemeRegistry)}.</p>
 *
 * <p>The {@link #secret()} is sensitive: persist it encrypted (see {@link SecretCipher}) and never
 * log it. {@link #toString()} intentionally omits it.</p>
 */
public final class CotpCredential {

    private final String cardId;
    private final CotpSecret secret;
    private final String schemeId;
    private final Instant createdAt;
    private final Instant expiresAt;

    private CotpCredential(Builder b) {
        if (b.cardId == null || b.cardId.isBlank()) {
            throw new CotpConfigurationException("cardId must be non-blank");
        }
        if (b.cardId.indexOf('|') >= 0) {
            throw new CotpConfigurationException("cardId must not contain '|'");
        }
        this.secret = Objects.requireNonNull(b.secret, "secret");
        if (b.schemeId == null || b.schemeId.isBlank()) {
            throw new CotpConfigurationException("schemeId must be non-blank");
        }
        if (b.schemeId.indexOf('|') >= 0) {
            throw new CotpConfigurationException("schemeId must not contain '|'");
        }
        this.cardId = b.cardId;
        this.schemeId = b.schemeId;
        this.createdAt = b.createdAt;
        this.expiresAt = b.expiresAt;
    }

    /** @return a builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param card card to snapshot
     * @return a credential built from a generated card
     */
    public static CotpCredential fromCard(CotpCard card) {
        Objects.requireNonNull(card, "card");
        return builder()
                .cardId(card.cardId())
                .secret(card.secret())
                .schemeId(card.scheme().version())
                .createdAt(card.createdAt())
                .expiresAt(card.expiresAt())
                .build();
    }

    public String cardId() {
        return cardId;
    }

    public CotpSecret secret() {
        return secret;
    }

    /** @return version of the card scheme (e.g. {@code "COTP/v1"}). */
    public String schemeId() {
        return schemeId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Resolves the scheme from a registry.
     *
     * @throws CotpException if the scheme version is unknown
     */
    public CotpScheme resolveScheme(SchemeRegistry registry) {
        return Objects.requireNonNull(registry, "registry").resolve(schemeId);
    }

    /**
     * Regenerates the card using the given scheme (the scheme version MUST match {@link #schemeId()}).
     *
     * @throws IllegalArgumentException if the scheme version does not match
     */
    public CotpCard toCard(CotpScheme scheme) {
        Objects.requireNonNull(scheme, "scheme");
        if (!scheme.version().equals(schemeId)) {
            throw new CotpConfigurationException("Scheme version mismatch: credential has '" + schemeId
                    + "' but scheme is '" + scheme.version() + "'");
        }
        return new CotpCardGenerator(scheme).generate(cardId, secret, createdAt, expiresAt);
    }

    /**
     * Regenerates the card resolving the scheme from a registry.
     *
     * @throws CotpException if the scheme version is unknown
     */
    public CotpCard toCard(SchemeRegistry registry) {
        return toCard(resolveScheme(registry));
    }

    @Override
    public String toString() {
        return "CotpCredential{cardId=" + cardId + ", schemeId=" + schemeId
                + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt + "}";
    }

    /** Mutable builder for {@link CotpCredential}. */
    public static final class Builder {
        private String cardId;
        private CotpSecret secret;
        private String schemeId;
        private Instant createdAt;
        private Instant expiresAt;

        private Builder() {
        }

        public Builder cardId(String cardId) {
            this.cardId = cardId;
            return this;
        }

        public Builder secret(CotpSecret secret) {
            this.secret = secret;
            return this;
        }

        public Builder schemeId(String schemeId) {
            this.schemeId = schemeId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public CotpCredential build() {
            return new CotpCredential(this);
        }
    }
}
