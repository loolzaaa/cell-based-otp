package com.cotp.demo;

import com.cotp.BurnPolicy;
import com.cotp.Cell;
import com.cotp.CellPosition;
import com.cotp.ChallengeMode;
import com.cotp.Cotp;
import com.cotp.CotpCard;
import com.cotp.CotpCardPrinter;
import com.cotp.CotpChallenge;
import com.cotp.CotpPolicy;
import com.cotp.CotpScheme;
import com.cotp.VerificationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-user demo session that drives the <b>real</b> COTP library.
 *
 * <p>All state is transient and in-memory: the card, its PIN hash, the challenge map, the burn and
 * throttle stores (owned by the library verifier) and the audit log. Nothing is persisted and no
 * secret (card secret, submitted word or PIN) is ever written to the audit log.</p>
 */
final class DemoSession {

    private static final int MAX_AUDIT_ENTRIES = 300;

    // Configuration chosen at enrolment.
    private String layout = "SINGLE";
    private BurnPolicy burnPolicy = BurnPolicy.CELL;
    private ChallengeMode challengeMode = ChallengeMode.RANDOM;
    private int ttlSeconds = 120;
    private int cooldownSeconds = 30;
    private int maxFailures = 3;
    private int lockSeconds = 30;
    private int pinIterations = 10_000;

    private Cotp cotp;
    private CotpScheme scheme;
    private CotpCard card;
    private String pinHash;

    private final Map<String, CotpChallenge> challenges = new LinkedHashMap<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private long auditSeq = 0;

    // ------------------------------------------------------------------ operations

    /** (Re-)enrols a card with the requested configuration and PIN. */
    synchronized Map<String, Object> enroll(Map<String, Object> req) {
        layout = enumName(req.get("layout"), layout, "SINGLE", "WORD_PAIRS");
        challengeMode = parseChallengeMode(req.get("challengeMode"), challengeMode);

        // Mirror CotpPolicy.Builder: TIME_BASED without an explicit burn policy defaults the burn
        // policy to CHALLENGE; explicitly requesting TIME_BASED + CELL/COOLDOWN is rejected below.
        boolean burnExplicit = isPresent(req.get("burnPolicy"));
        if (burnExplicit) {
            burnPolicy = parseBurnPolicy(req.get("burnPolicy"), burnPolicy);
        } else if (challengeMode == ChallengeMode.TIME_BASED) {
            burnPolicy = BurnPolicy.CHALLENGE;
        }
        ttlSeconds = clampInt(req.get("ttlSeconds"), ttlSeconds, 5, 3600);
        cooldownSeconds = clampInt(req.get("cooldownSeconds"), cooldownSeconds, 1, 86400);
        maxFailures = clampInt(req.get("maxFailures"), maxFailures, 1, 100);
        lockSeconds = clampInt(req.get("lockSeconds"), lockSeconds, 1, 86400);
        pinIterations = clampInt(req.get("pinIterations"), pinIterations, 1000, 1_000_000);

        if (challengeMode == ChallengeMode.TIME_BASED
                && (burnPolicy == BurnPolicy.CELL || burnPolicy == BurnPolicy.COOLDOWN)) {
            throw new IllegalArgumentException("TIME_BASED challenge mode is incompatible with BurnPolicy."
                    + burnPolicy + " (a time-derived cell would be rejected on reuse). "
                    + "Use CHALLENGE or NONE, or switch challengeMode to RANDOM.");
        }

        String pin = str(req.get("pin"));
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("A demo PIN is required");
        }

        CotpScheme.Builder schemeBuilder = CotpScheme.builder();
        if ("WORD_PAIRS".equals(layout)) {
            schemeBuilder.positions(List.of(CellPosition.TOP, CellPosition.BOTTOM));
        }
        scheme = schemeBuilder.build();

        CotpPolicy policy = CotpPolicy.builder()
                .challengeMode(challengeMode)
                .burnPolicy(burnPolicy)
                .challengeTtl(Duration.ofSeconds(ttlSeconds))
                .cellCooldown(Duration.ofSeconds(cooldownSeconds))
                .maxFailures(maxFailures)
                .lockDuration(Duration.ofSeconds(lockSeconds))
                .pinIterations(pinIterations)
                .build();

        cotp = new Cotp(scheme, policy);
        card = cotp.generateCard(cotp.newCardId());
        pinHash = cotp.newPinHash(pin.toCharArray());

        challenges.clear();
        audit.clear();
        auditSeq = 0;

        event("ENROLL", "Card " + card.cardId() + " generated"
                + " [layout=" + layout + ", mode=" + challengeMode
                + ", burn=" + burnPolicy + ", ttl=" + ttlSeconds + "s]");

        Map<String, Object> out = state();
        if (!burnExplicit && challengeMode == ChallengeMode.TIME_BASED) {
            out.put("notice", "TIME_BASED without an explicit burn policy defaults BurnPolicy to CHALLENGE.");
        }
        return out;
    }

    /** Issues a challenge, either via the selected mode or for an explicitly requested cell. */
    synchronized Map<String, Object> challenge(Map<String, Object> req) {
        requireEnrolled();
        Instant now = Instant.now();
        String forcedCellId = str(req.get("cellId"));

        CotpChallenge ch;
        if (forcedCellId != null && !forcedCellId.isBlank()) {
            Cell cell = Cell.parse(forcedCellId.trim());
            if (!card.grid().containsKey(cell.id())) {
                throw new IllegalArgumentException("Cell " + cell.id() + " is not part of this card");
            }
            ch = new CotpChallenge(cotp.challengeGenerator().newChallengeId(), card.cardId(), cell,
                    ChallengeMode.RANDOM, now, now.plus(policy().challengeTtl()));
        } else if (challengeMode == ChallengeMode.TIME_BASED) {
            ch = cotp.challengeGenerator().timeBased(card, now);
        } else {
            ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        }

        challenges.put(ch.challengeId(), ch);
        event("CHALLENGE", "Issued cell " + ch.cellId() + " (" + ch.mode() + ", "
                + secondsUntil(ch.expiresAt(), now) + "s TTL)");
        return state();
    }

    /** Verifies a submitted word + PIN against a previously issued challenge. */
    synchronized Map<String, Object> verify(Map<String, Object> req) {
        requireEnrolled();
        String challengeId = str(req.get("challengeId"));
        String word = str(req.get("word"));
        String pin = str(req.get("pin"));

        Map<String, Object> out = new LinkedHashMap<>();
        CotpChallenge ch = challengeId == null ? null : challenges.get(challengeId);
        if (ch == null) {
            out.put("result", VerificationResult.INVALID_REQUEST.name());
            out.put("success", Boolean.FALSE);
            out.put("message", "No such challenge — issue a challenge first.");
            event("VERIFY", "Unknown challengeId -> INVALID_REQUEST");
            out.putAll(state());
            return out;
        }

        VerificationResult result = cotp.verifier().verify(
                card,
                pinHash,
                ch,
                word == null ? "" : word,
                pin == null ? null : pin.toCharArray(),
                Instant.now());

        out.put("result", result.name());
        out.put("success", result.isSuccess());
        out.put("message", describe(result));
        event("VERIFY", "Challenge " + challengeId + " cell " + ch.cellId() + " -> " + result.name());

        Instant locked = cotp.verifier().throttleStore().lockedUntil(card.cardId());
        if (locked != null && locked.isAfter(Instant.now())) {
            event("LOCKOUT", "Card temporarily locked for "
                    + secondsUntil(locked, Instant.now()) + "s after "
                    + cotp.verifier().throttleStore().failureCount(card.cardId()) + " failures");
        }

        out.putAll(state());
        return out;
    }

    /** Clears the session back to "not enrolled". */
    synchronized Map<String, Object> reset() {
        cotp = null;
        scheme = null;
        card = null;
        pinHash = null;
        challenges.clear();
        audit.clear();
        auditSeq = 0;
        return state();
    }

    // ------------------------------------------------------------------ state

    synchronized Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrolled", card != null);
        m.put("config", configMap());
        m.put("audit", new ArrayList<>(audit));
        m.put("serverTime", Instant.now().toString());
        m.put("supported", supported());

        if (card == null) {
            return m;
        }

        m.put("cardId", card.cardId());
        m.put("pinHash", pinHash);
        m.put("scheme", Map.of(
                "version", scheme.version(),
                "layout", layout,
                "columns", scheme.columns(),
                "rows", scheme.rows(),
                "positions", scheme.positions(),
                "slotCount", scheme.slotCount()));
        m.put("cells", cellsJson());
        m.put("printed", CotpCardPrinter.render(card));

        List<String> burned = new ArrayList<>(cotp.verifier().burnStore().burnedCells(card.cardId()));
        burned.sort(String::compareTo);
        m.put("burnedCells", burned);
        m.put("cooldownUses", cooldownUses());

        int failures = cotp.verifier().throttleStore().failureCount(card.cardId());
        m.put("failureCount", failures);
        Instant locked = cotp.verifier().throttleStore().lockedUntil(card.cardId());
        m.put("lockedUntil", locked == null ? null : locked.toString());
        m.put("lockedUntilEpochMs", locked == null ? null : locked.toEpochMilli());

        CotpChallenge latest = challenges.isEmpty() ? null
                : new ArrayList<>(challenges.values()).get(challenges.size() - 1);
        m.put("latestChallenge", latest == null ? null : challengeMap(latest));
        m.put("challengeCount", challenges.size());
        return m;
    }

    private Map<String, Object> configMap() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("layout", layout);
        c.put("burnPolicy", burnPolicy.name());
        c.put("challengeMode", challengeMode.name());
        c.put("ttlSeconds", ttlSeconds);
        c.put("cooldownSeconds", cooldownSeconds);
        c.put("maxFailures", maxFailures);
        c.put("lockSeconds", lockSeconds);
        c.put("pinIterations", pinIterations);
        return c;
    }

    private static Map<String, Object> supported() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("layouts", List.of("SINGLE", "WORD_PAIRS"));
        s.put("burnPolicies", List.of("CELL", "COOLDOWN", "CHALLENGE", "NONE"));
        s.put("challengeModes", List.of("RANDOM", "TIME_BASED"));
        return s;
    }

    private List<Map<String, Object>> cellsJson() {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Cell cell : scheme.cells()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", cell.id());
            c.put("column", String.valueOf(cell.column()));
            c.put("row", cell.row());
            c.put("position", cell.position().name());
            c.put("word", card.wordAt(cell));
            cells.add(c);
        }
        return cells;
    }

    private List<Map<String, Object>> cooldownUses() {
        List<Map<String, Object>> uses = new ArrayList<>();
        Instant now = Instant.now();
        for (Cell cell : scheme.cells()) {
            Instant last = cotp.verifier().burnStore().lastCellUse(card.cardId(), cell.id());
            if (last != null) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("cellId", cell.id());
                u.put("usedAt", last.toString());
                u.put("usedAtEpochMs", last.toEpochMilli());
                long remaining = Duration.between(now, last.plusSeconds(cooldownSeconds)).toMillis();
                u.put("cooldownRemainingMs", Math.max(0, remaining));
                uses.add(u);
            }
        }
        return uses;
    }

    private Map<String, Object> challengeMap(CotpChallenge ch) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("challengeId", ch.challengeId());
        c.put("cellId", ch.cellId());
        c.put("cell", Map.of(
                "column", String.valueOf(ch.cell().column()),
                "row", ch.cell().row(),
                "position", ch.cell().position().name()));
        c.put("mode", ch.mode().name());
        c.put("issuedAt", ch.issuedAt().toString());
        c.put("expiresAt", ch.expiresAt().toString());
        c.put("expiresAtEpochMs", ch.expiresAt().toEpochMilli());
        return c;
    }

    // ------------------------------------------------------------------ helpers

    private CotpPolicy policy() {
        return cotp.policy();
    }

    private void requireEnrolled() {
        if (card == null) {
            throw new IllegalStateException("No card enrolled yet — enrol first");
        }
    }

    private void event(String type, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", ++auditSeq);
        e.put("time", Instant.now().toString());
        e.put("type", type);
        e.put("message", message);
        audit.add(e);
        while (audit.size() > MAX_AUDIT_ENTRIES) {
            audit.remove(0);
        }
    }

    private static String describe(VerificationResult r) {
        return switch (r) {
            case OK -> "All factors matched — authentication successful.";
            case INVALID_WORD -> "The submitted word does not match the challenged cell.";
            case INVALID_PIN -> "The PIN is missing or incorrect.";
            case IDENTITY_MISMATCH -> "The identity anchor does not match.";
            case CHALLENGE_EXPIRED -> "The challenge TTL has elapsed.";
            case CHALLENGE_REUSED -> "This challenge was already consumed (replay blocked).";
            case CELL_ALREADY_USED -> "The challenged cell is burned / still in cooldown.";
            case CARD_EXPIRED -> "The card has expired.";
            case CARD_EXHAUSTED -> "All cells are burned — the card must be re-enrolled.";
            case THROTTLED -> "Too many failures — the card is temporarily locked.";
            case INVALID_REQUEST -> "Malformed request or forged/unknown cell.";
        };
    }

    private static long secondsUntil(Instant end, Instant now) {
        return Math.max(0, Duration.between(now, end).getSeconds());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isPresent(Object o) {
        return o != null && !String.valueOf(o).isBlank();
    }

    private static String enumName(Object o, String fallback, String... allowed) {
        String v = str(o);
        if (v == null) {
            return fallback;
        }
        v = v.trim().toUpperCase();
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        return fallback;
    }

    private static BurnPolicy parseBurnPolicy(Object o, BurnPolicy fallback) {
        String v = str(o);
        if (v == null) {
            return fallback;
        }
        try {
            return BurnPolicy.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static ChallengeMode parseChallengeMode(Object o, ChallengeMode fallback) {
        String v = str(o);
        if (v == null) {
            return fallback;
        }
        try {
            return ChallengeMode.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int clampInt(Object o, int fallback, int min, int max) {
        int v = fallback;
        if (o instanceof Number n) {
            v = n.intValue();
        } else if (o != null) {
            try {
                v = Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                v = fallback;
            }
        }
        return Math.max(min, Math.min(max, v));
    }
}
