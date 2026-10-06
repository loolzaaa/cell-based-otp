package com.cotp;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;

/**
 * High-level entry point of the COTP library, analogous in spirit to a TOTP library facade.
 *
 * <p>Typical server-side lifecycle (single policy):</p>
 * <pre>{@code
 * Cotp cotp = new Cotp();                         // defaults: 5x5, BIP-39 dictionary, SHA-256
 *
 * // Enrolment
 * CotpEnrollment enr = cotp.enroll("CARD-ID", pin);      // card + pinHash + credential
 * // -> print enr.card().grid(); persist credential (encrypted) + pinHash
 *
 * // Login / step-up
 * CotpChallenge ch = cotp.challenge(enr.card(), "kiosk_login", Instant.now());
 * VerificationResult r = cotp.authenticate(enr.card(), enr.pinHash(), ch, word, pin, Instant.now());
 * ClientError client = r.toClientError();          // generic, anti-oracle
 * }</pre>
 *
 * <p>With a {@link CotpPolicyResolver} the burn/TTL/throttle policy is chosen per context, while the
 * card scheme and the burn/throttle stores are shared.</p>
 *
 * <p>The library is stateless with respect to storage: persistence of secrets, PIN hashes, burn and
 * throttle state is delegated to the caller through {@link BurnStore} and {@link ThrottleStore}.</p>
 */
public final class Cotp {

    private final CotpScheme scheme;
    private final CotpPolicy policy;
    private final CotpPolicyResolver resolver;
    private final CotpEventSink eventSink;
    private final BurnStore burnStore;
    private final ThrottleStore throttleStore;
    private final CotpCardGenerator cardGenerator;
    private final CotpChallengeGenerator challengeGenerator;
    private final CotpVerifier verifier;
    private final PinHasher pinHasher;
    private final SecureRandom random = new SecureRandom();

    /** Creates an instance with default scheme and policy. */
    public Cotp() {
        this(CotpScheme.defaults(), CotpPolicy.defaults(), null, CotpEventSink.NOOP);
    }

    /** Creates an instance with the given scheme and the default policy. */
    public Cotp(CotpScheme scheme) {
        this(scheme, CotpPolicy.defaults(), null, CotpEventSink.NOOP);
    }

    /** Creates an instance with the default scheme and the given policy. */
    public Cotp(CotpPolicy policy) {
        this(CotpScheme.defaults(), policy, null, CotpEventSink.NOOP);
    }

    /**
     * @param scheme card scheme (version/layout/dictionary/time-step)
     * @param policy operational policy (mode/burn/cooldown/TTL/throttle/PIN)
     */
    public Cotp(CotpScheme scheme, CotpPolicy policy) {
        this(scheme, policy, null, CotpEventSink.NOOP);
    }

    /**
     * @param scheme    card scheme
     * @param policy    operational policy
     * @param eventSink sink for audit/observability events (use {@link CotpEventSink#NOOP})
     */
    public Cotp(CotpScheme scheme, CotpPolicy policy, CotpEventSink eventSink) {
        this(scheme, policy, null, eventSink);
    }

    /**
     * @param scheme   card scheme
     * @param resolver per-context policy resolver (default policy is {@code resolver.resolve(null)})
     */
    public Cotp(CotpScheme scheme, CotpPolicyResolver resolver) {
        this(scheme, null, resolver, CotpEventSink.NOOP);
    }

    /**
     * @param scheme    card scheme
     * @param resolver  per-context policy resolver
     * @param eventSink sink for audit/observability events
     */
    public Cotp(CotpScheme scheme, CotpPolicyResolver resolver, CotpEventSink eventSink) {
        this(scheme, null, resolver, eventSink);
    }

    private Cotp(CotpScheme scheme, CotpPolicy policy, CotpPolicyResolver resolver, CotpEventSink eventSink) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.resolver = resolver;
        this.policy = resolver != null
                ? Objects.requireNonNull(resolver.resolve(null), "default policy")
                : Objects.requireNonNull(policy, "policy");
        this.burnStore = new InMemoryBurnStore();
        this.throttleStore = new InMemoryThrottleStore();
        this.cardGenerator = new CotpCardGenerator(scheme);
        this.challengeGenerator = new CotpChallengeGenerator(this.policy, eventSink);
        this.verifier = new CotpVerifier(this.policy, burnStore, throttleStore, eventSink);
        this.pinHasher = new PinHasher(this.policy.pinIterations());
    }

    /** @return the scheme used by this instance. */
    public CotpScheme scheme() {
        return scheme;
    }

    /** @return the default policy (resolver fallback when a resolver is configured). */
    public CotpPolicy policy() {
        return policy;
    }

    /** @return the configured resolver, or {@code null} if a single policy is used. */
    public CotpPolicyResolver policyResolver() {
        return resolver;
    }

    /** @return the event sink used by this instance. */
    public CotpEventSink eventSink() {
        return eventSink;
    }

    /** @return the shared burn store. */
    public BurnStore burnStore() {
        return burnStore;
    }

    /** @return the shared throttle store. */
    public ThrottleStore throttleStore() {
        return throttleStore;
    }

    /** @return the card generator. */
    public CotpCardGenerator cardGenerator() {
        return cardGenerator;
    }

    /** @return the challenge generator for the default policy. */
    public CotpChallengeGenerator challengeGenerator() {
        return challengeGenerator;
    }

    /** @return the verifier for the default policy (shares the burn/throttle stores). */
    public CotpVerifier verifier() {
        return verifier;
    }

    /** @return the PIN hasher for the default policy. */
    public PinHasher pinHasher() {
        return pinHasher;
    }

    private CotpPolicy policyFor(String context) {
        return resolver != null ? resolver.resolve(context) : policy;
    }

    /**
     * Generates a card with a fresh random secret.
     *
     * @param cardId public card identifier
     * @return generated card
     */
    public CotpCard generateCard(String cardId) {
        return cardGenerator.generate(cardId);
    }

    /**
     * Regenerates a card from a persisted secret.
     *
     * @param cardId public card identifier
     * @param secret card secret
     * @return card whose grid is deterministically derived from the secret
     */
    public CotpCard generateCard(String cardId, CotpSecret secret) {
        return cardGenerator.generate(cardId, secret);
    }

    /**
     * Enrols a new card with a fresh id and the given PIN.
     *
     * @param pin PIN characters (not stored; only its hash is returned)
     * @return the enrolment (card + PIN hash + credential)
     */
    public CotpEnrollment enroll(char[] pin) {
        return enroll(newCardId(), pin, null);
    }

    /**
     * Enrols a new card with the given id and PIN (default policy for PIN iterations).
     */
    public CotpEnrollment enroll(String cardId, char[] pin) {
        return enroll(cardId, pin, null);
    }

    /**
     * Enrols a new card with the given id and PIN, using the policy resolved for {@code context}
     * (for example the PBKDF2 iteration count).
     *
     * @param cardId  public card identifier
     * @param pin     PIN characters
     * @param context policy context, or {@code null} for the default policy
     * @return the enrolment (card + PIN hash + credential)
     */
    public CotpEnrollment enroll(String cardId, char[] pin, String context) {
        Objects.requireNonNull(pin, "pin");
        CotpCard card = generateCard(cardId);
        String pinHash = new PinHasher(policyFor(context).pinIterations()).hash(pin);
        return new CotpEnrollment(card, pinHash, CotpCredential.fromCard(card));
    }

    /** @return a fresh public card identifier (80-bit, Base32). */
    public String newCardId() {
        byte[] b = new byte[10];
        random.nextBytes(b);
        return Base32.encode(b);
    }

    /**
     * Issues a challenge for {@code context} (uses the policy resolved for it).
     *
     * @param card    card to challenge
     * @param context scenario name (e.g. {@code "kiosk_login"}), or {@code null}
     * @param now     current instant
     * @return the issued challenge
     */
    public CotpChallenge challenge(CotpCard card, String context, Instant now) {
        return new CotpChallengeGenerator(policyFor(context), eventSink).newChallenge(card, now, burnStore, context);
    }

    /**
     * Authenticates a response, using the policy resolved for the challenge's
     * {@link CotpChallenge#context() context}.
     *
     * @param card      card the challenge belongs to
     * @param pinHash   stored PIN hash, or {@code null} to skip the PIN factor
     * @param challenge issued challenge
     * @param word      user-provided word
     * @param pin       user-provided PIN, or {@code null}
     * @param now       current instant
     * @return the detailed (internal) verification result; use {@link VerificationResult#toClientError()}
     *         for the client-facing outcome
     */
    public VerificationResult authenticate(CotpCard card, String pinHash, CotpChallenge challenge,
                                           String word, char[] pin, Instant now) {
        return new CotpVerifier(policyFor(challenge == null ? null : challenge.context()),
                burnStore, throttleStore, eventSink)
                .verify(card, pinHash, challenge, word, pin, now);
    }

    /**
     * Hashes a PIN for storage using the default policy's iteration count.
     *
     * @param pin PIN characters
     * @return encoded PIN hash
     */
    public String newPinHash(char[] pin) {
        return pinHasher.hash(pin);
    }

    /**
     * Verifies a PIN against a stored hash.
     *
     * @param pin  candidate PIN
     * @param hash stored PIN hash
     * @return {@code true} if it matches
     */
    public boolean verifyPin(char[] pin, String hash) {
        return pinHasher.verify(pin, hash);
    }
}
