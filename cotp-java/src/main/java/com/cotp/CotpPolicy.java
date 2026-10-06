package com.cotp;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable <b>policy</b> of a COTP deployment: challenge mode and lifetime, burn/cooldown rules,
 * PIN hashing parameters and brute-force throttling.
 *
 * <p>Policy is operational and is independent of a card's {@link CotpScheme}. It is applied by the
 * challenge generator and the verifier; it never affects how the grid is derived.</p>
 */
public final class CotpPolicy {

    private final ChallengeMode challengeMode;
    private final Duration challengeTtl;
    private final BurnPolicy burnPolicy;
    private final Duration cellCooldown;
    private final int pinIterations;
    private final int maxFailures;
    private final Duration lockDuration;

    private CotpPolicy(Builder b) {
        this.challengeMode = b.challengeMode;
        this.challengeTtl = b.challengeTtl;
        this.burnPolicy = b.burnPolicy;
        this.cellCooldown = b.cellCooldown;
        this.pinIterations = b.pinIterations;
        this.maxFailures = b.maxFailures;
        this.lockDuration = b.lockDuration;

        if (challengeTtl == null || challengeTtl.isZero() || challengeTtl.isNegative()) {
            throw new CotpConfigurationException("challengeTtl must be a positive duration");
        }
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new CotpConfigurationException("lockDuration must be a positive duration");
        }
        if (cellCooldown == null || cellCooldown.isZero() || cellCooldown.isNegative()) {
            throw new CotpConfigurationException("cellCooldown must be a positive duration");
        }
        if (pinIterations < 1000) {
            throw new CotpConfigurationException("pinIterations must be >= 1000");
        }
        if (maxFailures < 1) {
            throw new CotpConfigurationException("maxFailures must be >= 1");
        }
        if (challengeMode == ChallengeMode.TIME_BASED
                && (burnPolicy == BurnPolicy.CELL || burnPolicy == BurnPolicy.COOLDOWN)) {
            throw new CotpConfigurationException("TIME_BASED challenge mode is incompatible with BurnPolicy."
                    + burnPolicy + ": a time-derived cell could be rejected on reuse. "
                    + "Use BurnPolicy.CHALLENGE or NONE.");
        }
    }

    /** @return the default policy (RANDOM mode, CELL burn, 120 s TTL, 5 failures / 15 min lock). */
    public static CotpPolicy defaults() {
        return builder().build();
    }

    /** @return a fresh builder pre-populated with defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public ChallengeMode challengeMode() {
        return challengeMode;
    }

    public Duration challengeTtl() {
        return challengeTtl;
    }

    public BurnPolicy burnPolicy() {
        return burnPolicy;
    }

    /** @return minimum interval before a cell may be reused under {@link BurnPolicy#COOLDOWN}. */
    public Duration cellCooldown() {
        return cellCooldown;
    }

    public int pinIterations() {
        return pinIterations;
    }

    /** @return number of consecutive failed verifications before temporary lockout. */
    public int maxFailures() {
        return maxFailures;
    }

    /** @return lockout duration applied after {@link #maxFailures()} consecutive failures. */
    public Duration lockDuration() {
        return lockDuration;
    }

    /** Mutable builder for {@link CotpPolicy}. */
    public static final class Builder {
        private ChallengeMode challengeMode = ChallengeMode.RANDOM;
        private Duration challengeTtl = Duration.ofSeconds(120);
        private BurnPolicy burnPolicy = BurnPolicy.CELL;
        private boolean burnPolicyExplicit = false;
        private Duration cellCooldown = Duration.ofHours(24);
        private int pinIterations = 210_000;
        private int maxFailures = 5;
        private Duration lockDuration = Duration.ofMinutes(15);

        private Builder() {
        }

        /**
         * Challenge mode (default {@link ChallengeMode#RANDOM}). Selecting
         * {@link ChallengeMode#TIME_BASED} without an explicit {@link #burnPolicy(BurnPolicy)}
         * switches the default burn policy to {@link BurnPolicy#CHALLENGE}, because a time-derived
         * cell cannot be burned under {@link BurnPolicy#CELL}/{@link BurnPolicy#COOLDOWN} without
         * rejecting valid challenges.
         */
        public Builder challengeMode(ChallengeMode challengeMode) {
            this.challengeMode = Objects.requireNonNull(challengeMode, "challengeMode");
            if (challengeMode == ChallengeMode.TIME_BASED && !burnPolicyExplicit) {
                this.burnPolicy = BurnPolicy.CHALLENGE;
            }
            return this;
        }

        /** Burn policy (default {@link BurnPolicy#CELL}). */
        public Builder burnPolicy(BurnPolicy burnPolicy) {
            this.burnPolicy = Objects.requireNonNull(burnPolicy, "burnPolicy");
            this.burnPolicyExplicit = true;
            return this;
        }

        /** Challenge time-to-live (default 120 s). */
        public Builder challengeTtl(Duration challengeTtl) {
            this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl");
            return this;
        }

        /** Minimum interval before a cell may be reused under {@link BurnPolicy#COOLDOWN} (default 24 h). */
        public Builder cellCooldown(Duration cellCooldown) {
            this.cellCooldown = Objects.requireNonNull(cellCooldown, "cellCooldown");
            return this;
        }

        /** PBKDF2-HMAC-SHA256 iteration count for PIN hashing (default 210000). */
        public Builder pinIterations(int pinIterations) {
            this.pinIterations = pinIterations;
            return this;
        }

        /** Consecutive failure threshold before lockout (default 5). */
        public Builder maxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
            return this;
        }

        /** Lockout duration after too many failures (default 15 minutes). */
        public Builder lockDuration(Duration lockDuration) {
            this.lockDuration = Objects.requireNonNull(lockDuration, "lockDuration");
            return this;
        }

        /** @return an immutable policy. */
        public CotpPolicy build() {
            return new CotpPolicy(this);
        }
    }
}
