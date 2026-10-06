package ru.loolzaaa;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the {@link CotpPolicy} to use for a given application context (for example
 * {@code "kiosk_login"}, {@code "stepup"}, {@code "service_desk"}).
 *
 * <p>This lets one deployment apply different burn/TTL/throttle policies per scenario while sharing a
 * single card scheme and shared burn/throttle stores. A {@code null} context means "default".</p>
 */
@FunctionalInterface
public interface CotpPolicyResolver {

    /**
     * @param context free-form scenario name, or {@code null} for the default policy
     * @return the policy to apply (never {@code null})
     */
    CotpPolicy resolve(String context);

    /**
     * @param policy the single policy used regardless of context
     * @return a resolver that always returns {@code policy}
     */
    static CotpPolicyResolver fixed(CotpPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return context -> policy;
    }

    /**
     * @param defaultPolicy policy returned for {@code null} or unknown contexts
     * @param byContext     per-context policies
     * @return a map-backed resolver
     */
    static CotpPolicyResolver of(CotpPolicy defaultPolicy, Map<String, CotpPolicy> byContext) {
        Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        Objects.requireNonNull(byContext, "byContext");
        return context -> context == null ? defaultPolicy : byContext.getOrDefault(context, defaultPolicy);
    }

    /**
     * @return recommended presets for the three canonical scenarios plus a default:
     * <ul>
     *   <li>{@code kiosk_login} — {@link BurnPolicy#COOLDOWN} 24 h (frequent public-kiosk logins);</li>
     *   <li>{@code stepup} — {@link BurnPolicy#CHALLENGE}, 60 s TTL (frequent step-up);</li>
     *   <li>{@code service_desk} — {@link BurnPolicy#CELL}, 120 s TTL (rare, high sensitivity);</li>
     *   <li>default — {@link BurnPolicy#CELL}.</li>
     * </ul>
     */
    static CotpPolicyResolver defaults() {
        CotpPolicy defaultPolicy = CotpPolicy.defaults();
        return of(defaultPolicy, Map.of(
                "kiosk_login", CotpPolicy.builder()
                        .burnPolicy(BurnPolicy.COOLDOWN)
                        .cellCooldown(Duration.ofHours(24))
                        .build(),
                "stepup", CotpPolicy.builder()
                        .burnPolicy(BurnPolicy.CHALLENGE)
                        .challengeTtl(Duration.ofSeconds(60))
                        .build(),
                "service_desk", CotpPolicy.builder()
                        .burnPolicy(BurnPolicy.CELL)
                        .build()));
    }
}
