package ru.loolzaaa;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of known {@link CotpScheme}s, keyed by {@link CotpScheme#version()}.
 *
 * <p>In a multi-version deployment a persisted {@link CotpCredential} stores only the scheme
 * version (e.g. {@code "COTP/v1"}); the registry resolves it back to the full scheme so the grid can
 * be regenerated. Two different schemes MUST NOT share a version.</p>
 */
public final class SchemeRegistry {

    private final Map<String, CotpScheme> byVersion = new ConcurrentHashMap<>();

    /** @return a registry pre-populated with {@link CotpScheme#defaults()}. */
    public static SchemeRegistry defaults() {
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(CotpScheme.defaults());
        return registry;
    }

    /**
     * @param schemes schemes to register
     * @return a registry containing exactly the given schemes
     */
    public static SchemeRegistry of(CotpScheme... schemes) {
        SchemeRegistry registry = new SchemeRegistry();
        for (CotpScheme scheme : schemes) {
            registry.register(scheme);
        }
        return registry;
    }

    /**
     * Registers a scheme.
     *
     * @param scheme scheme to register
     * @return this registry
     * @throws IllegalStateException if the version is already registered with a different scheme
     */
    public SchemeRegistry register(CotpScheme scheme) {
        Objects.requireNonNull(scheme, "scheme");
        CotpScheme existing = byVersion.putIfAbsent(scheme.version(), scheme);
        if (existing != null && !existing.equals(scheme)) {
            throw new IllegalStateException("Scheme version '" + scheme.version()
                    + "' is already registered with a different scheme");
        }
        return this;
    }

    /**
     * @param version scheme version, e.g. {@code "COTP/v1"}
     * @return the registered scheme
     * @throws CotpException if the version is unknown
     */
    public CotpScheme resolve(String version) {
        if (version == null) {
            throw new CotpException("Unknown scheme version: null");
        }
        CotpScheme scheme = byVersion.get(version);
        if (scheme == null) {
            throw new CotpException("Unknown scheme version: '" + version + "'. Registered: " + versions());
        }
        return scheme;
    }

    /** @return {@code true} if the version is registered (never throws for {@code null}). */
    public boolean contains(String version) {
        return version != null && byVersion.containsKey(version);
    }

    /** @return an immutable snapshot of the registered versions. */
    public Set<String> versions() {
        return Set.copyOf(byVersion.keySet());
    }
}
