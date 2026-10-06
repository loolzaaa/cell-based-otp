package ru.loolzaaa;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standalone, dependency-free verification harness for the COTP library.
 *
 * <p>It proves determinism and exercises the full protocol surface without requiring Maven or
 * JUnit. Exits with code 0 on success and 1 on the first failure batch.</p>
 *
 * <pre>java -cp build/classes ru.loolzaaa.CotpSelfTest</pre>
 */
public final class CotpSelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final HexFormat HEX = HexFormat.of();

    private CotpSelfTest() {
    }

    public static void main(String[] args) {
        rfc5869();
        determinism();
        referenceVector();
        uniquenessAndDifference();
        base32AndPin();
        constantTime();
        verificationFlows();
        burnFlows();
        atomicity();
        auditFixes();
        auditFixes2();
        p2Features();
        eventsAudit();
        credentialRegistry();
        typedExceptionsFacade();
        throttleFlow();
        timeBasedFlow();
        distributionSanity();
        cellParsing();

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ sections

    private static void rfc5869() {
        section("RFC 5869 HKDF-SHA256 Test Case 1");
        byte[] ikm = repeat((byte) 0x0b, 22);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.extract(salt, ikm);
        byte[] okm = Hkdf.expand(prk, info, 42);
        eq("PRK", "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HEX.formatHex(prk));
        eq("OKM", "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(okm));
    }

    private static void determinism() {
        section("Determinism (same inputs -> same grid)");
        CotpScheme config = CotpScheme.defaults();
        SecureRandom rng = new SecureRandom();
        boolean allSame = true;
        boolean allUnique = true;
        for (int t = 0; t < 200; t++) {
            byte[] secret = new byte[32];
            rng.nextBytes(secret);
            String cardId = "CARD-" + t;
            Map<String, String> a = CotpDerivation.buildGrid(
                    CotpDerivation.gridKey(secret, cardId, config), config);
            Map<String, String> b = CotpDerivation.buildGrid(
                    CotpDerivation.gridKey(secret.clone(), cardId, config), config);
            if (!a.equals(b)) {
                allSame = false;
            }
            if (new HashSet<>(a.values()).size() != a.size()) {
                allUnique = false;
            }
        }
        check("200 cards reproducible on re-derivation", allSame);
        check("all cards have globally unique words per grid", allUnique);

        CotpCardGenerator gen = new CotpCardGenerator(config);
        byte[] fixed = HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        CotpCard c1 = gen.generate("COTP-DEMO-0001", CotpSecret.of(fixed));
        CotpCard c2 = gen.derive(c1);
        eq("derive(card).grid equals card.grid", c1.grid().toString(), c2.grid().toString());
    }

    private static void referenceVector() {
        section("COTP v1 reference vector stability");
        byte[] secret = HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        String cardId = "COTP-DEMO-0001";
        CotpScheme config = CotpScheme.defaults();
        byte[] kGrid = CotpDerivation.gridKey(secret, cardId, config);
        eq("K_grid", "e8411322b623d405241e8d93ecc1bbdd8f2048f57e3c0dd342fc1041b661d9fd", HEX.formatHex(kGrid));

        Map<String, Integer> expectedIndex = new LinkedHashMap<>();
        expectedIndex.put("A1", 875);
        expectedIndex.put("A2", 849);
        expectedIndex.put("A3", 1762);
        expectedIndex.put("A4", 933);
        expectedIndex.put("A5", 794);
        expectedIndex.put("B1", 1385);
        expectedIndex.put("B2", 917);
        expectedIndex.put("B3", 17);
        expectedIndex.put("B4", 1539);
        expectedIndex.put("B5", 2016);
        expectedIndex.put("C1", 1273);
        expectedIndex.put("C2", 3);
        expectedIndex.put("C3", 1755);
        expectedIndex.put("C4", 470);
        expectedIndex.put("C5", 1308);
        expectedIndex.put("D1", 672);
        expectedIndex.put("D2", 365);
        expectedIndex.put("D3", 1467);
        expectedIndex.put("D4", 230);
        expectedIndex.put("D5", 2044);
        expectedIndex.put("E1", 1816);
        expectedIndex.put("E2", 360);
        expectedIndex.put("E3", 520);
        expectedIndex.put("E4", 929);
        expectedIndex.put("E5", 300);
        for (Map.Entry<String, Integer> e : expectedIndex.entrySet()) {
            int actual = CotpDerivation.wordIndex(kGrid, Cell.parse(e.getKey()), 0, config);
            eq("index " + e.getKey(), e.getValue(), actual);
        }

        Map<String, String> grid = CotpDerivation.buildGrid(kGrid, config);
        eq("word A1", "hope", grid.get("A1"));
        eq("word C2", "about", grid.get("C2"));
        eq("word E5", "cereal", grid.get("E5"));
        eq("word D5", "zebra", grid.get("D5"));

        byte[] challengeKey = CotpDerivation.challengeKey(secret, cardId, config);
        // Stable, deterministic selection for T = 1 (Unix time 59 s, step 30).
        int idx = CotpDerivation.selectSlotIndex(challengeKey, 1, config.slotCount(), config);
        check("time-based slot index in range", idx >= 0 && idx < config.slotCount());
    }

    private static void uniquenessAndDifference() {
        section("Uniqueness and sensitivity");
        CotpScheme config = CotpScheme.defaults();
        byte[] s1 = repeat((byte) 0x11, 32);
        byte[] s2 = repeat((byte) 0x11, 32);
        s2[0] = 0x12; // change one bit
        Map<String, String> g1 = CotpDerivation.buildGrid(CotpDerivation.gridKey(s1, "X", config), config);
        Map<String, String> g2 = CotpDerivation.buildGrid(CotpDerivation.gridKey(s2, "X", config), config);
        check("one-bit secret change alters the grid", !g1.equals(g2));

        Map<String, String> g3 = CotpDerivation.buildGrid(CotpDerivation.gridKey(s1, "Y", config), config);
        check("different cardId with same secret alters the grid", !g1.equals(g3));
    }

    private static void base32AndPin() {
        section("Base32 and PIN hashing");
        byte[] data = {0x00, 0x01, 0x02, (byte) 0xff, 0x10};
        byte[] rt = Base32.decode(Base32.encode(data));
        check("Base32 round-trip", java.util.Arrays.equals(data, rt));

        byte[] secret = repeat((byte) 0x0b, 32);
        eq("Base32 secret length", 52, CotpSecret.of(secret).toBase32().length());

        PinHasher hasher = new PinHasher(10_000);
        String hash = hasher.hash("1234".toCharArray());
        check("PIN verify correct", hasher.verify("1234".toCharArray(), hash));
        check("PIN verify wrong", !hasher.verify("0000".toCharArray(), hash));
        check("PIN verify null-safe", !hasher.verify("1234".toCharArray(), null));
    }

    private static void constantTime() {
        section("Constant-time word comparison");
        check("trim + case-insensitive match", ConstantTime.wordsMatch("  Hope ", "hope"));
        check("mismatch detected", !ConstantTime.wordsMatch("hope", "home"));
        check("null candidate", !ConstantTime.wordsMatch(null, "hope"));
    }

    private static void verificationFlows() {
        section("Verification flows");
        CotpScheme config = CotpScheme.defaults();
        Cotp cotp = new Cotp(config);
        byte[] secret = HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        CotpCard card = cotp.generateCard("COTP-DEMO-0001", CotpSecret.of(secret));
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        String word = card.wordAt(ch.cell());
        eq("correct word + PIN -> OK", VerificationResult.OK,
                cotp.verifier().verify(card, pinHash, ch, word, "1234".toCharArray(), now));

        CotpChallenge ch2 = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        eq("wrong word -> INVALID_WORD", VerificationResult.INVALID_WORD,
                cotp.verifier().verify(card, pinHash, ch2, "not-a-word", "1234".toCharArray(), now));

        CotpChallenge ch3 = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        eq("wrong PIN -> INVALID_PIN", VerificationResult.INVALID_PIN,
                cotp.verifier().verify(card, pinHash, ch3, card.wordAt(ch3.cell()), "9999".toCharArray(), now));

        eq("expired challenge -> CHALLENGE_EXPIRED", VerificationResult.CHALLENGE_EXPIRED,
                cotp.verifier().verify(card, pinHash, ch3, card.wordAt(ch3.cell()), "1234".toCharArray(),
                        now.plusSeconds(1000)));
    }

    private static void burnFlows() {
        section("Burn / replay semantics");
        CotpScheme config = CotpScheme.defaults();
        Cotp cotp = new Cotp(config);
        byte[] secret = repeat((byte) 0x2a, 32);
        CotpCard card = cotp.generateCard("BURN-CARD", CotpSecret.of(secret));
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        eq("first use OK", VerificationResult.OK,
                cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()), "1234".toCharArray(), now));

        eq("replay same challenge -> CHALLENGE_REUSED", VerificationResult.CHALLENGE_REUSED,
                cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()), "1234".toCharArray(), now));

        CotpChallenge sameCell = new CotpChallenge(cotp.challengeGenerator().newChallengeId(),
                card.cardId(), ch.cell(), ChallengeMode.RANDOM, now, now.plusSeconds(120));
        eq("burned cell reuse -> CELL_ALREADY_USED", VerificationResult.CELL_ALREADY_USED,
                cotp.verifier().verify(card, pinHash, sameCell, card.wordAt(ch.cell()), "1234".toCharArray(), now));

        Cotp cotp2 = new Cotp(CotpPolicy.builder().burnPolicy(BurnPolicy.CHALLENGE).build());
        CotpCard card2 = cotp2.generateCard("CB-CARD", CotpSecret.of(secret));
        Instant t = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge c1 = cotp2.challengeGenerator().random(card2, t, cotp2.verifier().burnStore());
        eq("CHALLENGE policy first OK", VerificationResult.OK,
                cotp2.verifier().verify(card2, null, c1, card2.wordAt(c1.cell()), null, t));
        CotpChallenge c2 = cotp2.challengeGenerator().random(card2, t.plusSeconds(1), cotp2.verifier().burnStore());
        eq("CHALLENGE policy second OK (cells reusable)", VerificationResult.OK,
                cotp2.verifier().verify(card2, null, c2, card2.wordAt(c2.cell()), null, t.plusSeconds(1)));
    }

    private static void atomicity() {
        section("Atomic one-time marking under concurrency");
        InMemoryBurnStore store = new InMemoryBurnStore();
        int threads = 16;

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger challengeWins = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cellWins = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (store.markChallengeUsed("CARD-1", "CH-1", Instant.parse("2026-10-06T13:00:00Z"))) {
                    challengeWins.incrementAndGet();
                }
                if (store.markCellBurned("CARD-1", "C2")) {
                    cellWins.incrementAndGet();
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        eq("exactly one thread wins the challenge", 1, challengeWins.get());
        eq("exactly one thread wins the cell", 1, cellWins.get());
    }

    private static void auditFixes() {
        section("Audit fixes (regression)");

        // F1: atomic failure counter
        InMemoryThrottleStore throttle = new InMemoryThrottleStore();
        int incThreads = 16;
        int perThread = 2000;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread[] workers = new Thread[incThreads];
        for (int i = 0; i < incThreads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < perThread; j++) {
                    throttle.incrementFailure("CARD-X");
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        eq("atomic failure counter = threads*perThread", incThreads * perThread, throttle.failureCount("CARD-X"));

        // F2: canonical grid order preserved
        CotpScheme config = CotpScheme.defaults();
        CotpCard card = new CotpCardGenerator(config).generate("ORDER-CARD", CotpSecret.of(repeat((byte) 0x07, 32)));
        java.util.List<String> expectedOrder = new java.util.ArrayList<>();
        for (Cell c : config.cells()) {
            expectedOrder.add(c.id());
        }
        eq("grid keys are in canonical order", expectedOrder, new java.util.ArrayList<>(card.grid().keySet()));

        // F3: forged TIME_BASED cell rejected
        CotpScheme tbConfig = CotpScheme.defaults();
        Cotp tb = new Cotp(tbConfig, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard tbCard = tb.generateCard("TB-CARD");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge real = tb.challengeGenerator().timeBased(tbCard, now);
        java.util.Set<String> windowCells = new java.util.HashSet<>();
        for (int d = -tbConfig.timeWindowBack(); d <= tbConfig.timeWindowForward(); d++) {
            windowCells.add(tb.challengeGenerator().timeBased(tbCard, now, d).cellId());
        }
        Cell other = tbConfig.cells().stream()
                .filter(c -> !windowCells.contains(c.id())).findFirst().orElseThrow();
        CotpChallenge forged = new CotpChallenge(tb.challengeGenerator().newChallengeId(), tbCard.cardId(),
                other, ChallengeMode.TIME_BASED, now, now.plusSeconds(120));
        eq("forged time-based cell -> INVALID_REQUEST", VerificationResult.INVALID_REQUEST,
                tb.verifier().verify(tbCard, null, forged, tbCard.wordAt(other), null, now));
        CotpChallenge legit = new CotpChallenge(tb.challengeGenerator().newChallengeId(), tbCard.cardId(),
                real.cell(), ChallengeMode.TIME_BASED, now, now.plusSeconds(120));
        eq("legitimate time-based cell -> OK", VerificationResult.OK,
                tb.verifier().verify(tbCard, null, legit, tbCard.wordAt(real.cell()), null, now));

        // F4: duplicate columns rejected
        check("duplicate columns rejected", throwsIae(() -> CotpScheme.builder()
                .columns(java.util.List.of('A', 'A', 'B')).rows(java.util.List.of(1)).build()));

        // F5: slots > dictionary rejected
        java.util.List<String> small = WordDictionary.bip39English().words().subList(0, 256);
        check("slots > dictionary rejected", throwsIae(() -> CotpScheme.builder()
                .dictionary(WordDictionary.of(small))
                .columns(new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed('A', 'Z')
                        .mapToObj(c -> (char) c).toList()))
                .rows(java.util.stream.IntStream.rangeClosed(1, 10).boxed().toList())
                .build()));

        // F6: card generated with another version still verifies (uses card.config())
        CotpScheme v2 = CotpScheme.builder().version("COTP/v2").build();
        CotpCard v2Card = new CotpCardGenerator(v2).generate("V2-CARD", CotpSecret.of(repeat((byte) 0x09, 32)));
        Cotp defaultCotp = new Cotp();
        CotpChallenge v2ch = defaultCotp.challengeGenerator().random(v2Card, now, defaultCotp.verifier().burnStore());
        eq("card of another version verifies via card.config()", VerificationResult.OK,
                defaultCotp.verifier().verify(v2Card, null, v2ch, v2Card.wordAt(v2ch.cell()), null, now));

        // F7: strict Base32
        check("Base32 'f' -> 0x66", java.util.Arrays.equals(new byte[]{0x66}, Base32.decode("MY")));
        check("Base32 'foobar'", java.util.Arrays.equals("foobar".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base32.decode("MZXW6YTBOI")));
        check("Base32 bad length rejected", throwsIae(() -> Base32.decode("A")));
        check("Base32 non-canonical trailing bits rejected", throwsIae(() -> Base32.decode("AB")));
        check("Base32 bad padding rejected", throwsIae(() -> Base32.decode("A2=")));

        // F8: negative TTL rejected
        check("negative challengeTtl rejected", throwsIae(() -> CotpPolicy.builder()
                .challengeTtl(java.time.Duration.ofSeconds(-5)).build()));

        // F12: unknown cell -> INVALID_REQUEST
        Cotp cotp = new Cotp();
        CotpCard c2 = cotp.generateCard("CELL-CARD");
        CotpChallenge bad = new CotpChallenge(cotp.challengeGenerator().newChallengeId(), c2.cardId(),
                new Cell('Z', 9, CellPosition.SINGLE), ChallengeMode.RANDOM, now, now.plusSeconds(120));
        eq("unknown cell -> INVALID_REQUEST", VerificationResult.INVALID_REQUEST,
                cotp.verifier().verify(c2, null, bad, "whatever", null, now));
    }

    private interface Thrower {
        void run();
    }

    private static boolean throwsIae(Thrower t) {
        try {
            t.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static java.util.Set<String> windowCells(Cotp cotp, CotpCard card, CotpScheme cfg, Instant ref) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int d = -cfg.timeWindowBack(); d <= cfg.timeWindowForward(); d++) {
            ids.add(cotp.challengeGenerator().timeBased(card, ref, d).cellId());
        }
        return ids;
    }

    private static void auditFixes2() {
        section("Audit fixes round 2 (regression)");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        byte[] secret = repeat((byte) 0x5a, 32);

        // F1: challenge generation uses the CARD's layout/version, not the generator's.
        CotpScheme paired = CotpScheme.builder()
                .positions(java.util.List.of(CellPosition.TOP, CellPosition.BOTTOM)).build();
        Cotp pairedCotp = new Cotp(paired);
        CotpCard pairedCard = pairedCotp.generateCard("PAIRS");
        Cotp def = new Cotp(); // SINGLE layout, different from the card
        CotpChallenge pch = def.challengeGenerator().random(pairedCard, now, def.verifier().burnStore());
        check("challenge cell belongs to card layout", paired.cells().contains(pch.cell()));
        eq("cross-layout challenge verifies OK", VerificationResult.OK,
                def.verifier().verify(pairedCard, null, pch, pairedCard.wordAt(pch.cell()), null, now));

        // F2: TIME_BASED window is anchored on `now`, not on `issuedAt`.
        CotpScheme tbCfg = CotpScheme.defaults();
        Cotp tb = new Cotp(tbCfg, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard tbCard = tb.generateCard("ANCHOR");
        Instant issued = now.minusSeconds(6L * tbCfg.timeStepSeconds());
        java.util.Set<String> nowWin = windowCells(tb, tbCard, tbCfg, now);
        java.util.Set<String> issuedWin = windowCells(tb, tbCard, tbCfg, issued);
        Cell target = tbCfg.cells().stream()
                .filter(c -> issuedWin.contains(c.id()) && !nowWin.contains(c.id()))
                .findFirst().orElse(null);
        if (target == null) {
            check("TIME_BASED anchored on now (no distinguishing cell; skipped)", true);
        } else {
            CotpChallenge stale = new CotpChallenge("STALE", tbCard.cardId(), target,
                    ChallengeMode.TIME_BASED, issued, now.plusSeconds(100_000));
            eq("challenge matching old issuedAt but not now -> INVALID_REQUEST",
                    VerificationResult.INVALID_REQUEST,
                    tb.verifier().verify(tbCard, null, stale, tbCard.wordAt(target), null, now));
        }

        // F3: CARD_EXHAUSTED reachable; generator throws CardExhaustedException.
        CotpScheme exCfg = CotpScheme.defaults();
        Cotp ex = new Cotp(exCfg);
        CotpCard exCard = ex.generateCard("EXH");
        CotpChallenge pre = ex.challengeGenerator().random(exCard, now, ex.verifier().burnStore());
        for (Cell cell : exCfg.cells()) {
            ex.verifier().burnStore().markCellBurned(exCard.cardId(), cell.id());
        }
        eq("all cells burned -> CARD_EXHAUSTED", VerificationResult.CARD_EXHAUSTED,
                ex.verifier().verify(exCard, null, pre, exCard.wordAt(pre.cell()), null, now));
        boolean thrown = false;
        try {
            ex.challengeGenerator().random(exCard, now, ex.verifier().burnStore());
        } catch (CardExhaustedException e) {
            thrown = true;
        }
        check("exhausted card -> CardExhaustedException", thrown);

        // F4: derive() uses the card's config.
        CotpScheme v2 = CotpScheme.builder().version("COTP/v2").build();
        CotpCard v2Card = new CotpCardGenerator(v2).generate("V2D", CotpSecret.of(secret));
        CotpCard derived = new CotpCardGenerator(CotpScheme.defaults()).derive(v2Card);
        eq("derive() uses card.config()", v2Card.grid().toString(), derived.grid().toString());

        // F5: identity mismatch does not throttle; blank anchor is skipped.
        Cotp idc = new Cotp();
        CotpCard idCard = idc.generateCard("ID");
        String idPin = idc.newPinHash("1234".toCharArray());
        for (int i = 0; i < 3; i++) {
            CotpChallenge ic = idc.challengeGenerator().random(idCard, now, idc.verifier().burnStore());
            eq("identity mismatch #" + i, VerificationResult.IDENTITY_MISMATCH,
                    idc.verifier().verify(idCard, idPin, ic, idCard.wordAt(ic.cell()), "1234".toCharArray(),
                            "WRONG", "RIGHT", now));
        }
        CotpChallenge ok = idc.challengeGenerator().random(idCard, now, idc.verifier().burnStore());
        eq("identity mismatch did not throttle", VerificationResult.OK,
                idc.verifier().verify(idCard, idPin, ok, idCard.wordAt(ok.cell()), "1234".toCharArray(),
                        "RIGHT", "RIGHT", now));
        CotpChallenge blank = idc.challengeGenerator().random(idCard, now, idc.verifier().burnStore());
        eq("blank anchor is skipped", VerificationResult.OK,
                idc.verifier().verify(idCard, idPin, blank, idCard.wordAt(blank.cell()), "1234".toCharArray(),
                        "  ", "RIGHT", now));

        // F6: reset() clears challenge ids.
        InMemoryBurnStore store = new InMemoryBurnStore();
        store.markChallengeUsed("C1", "CH-Z", Instant.parse("2026-10-06T13:00:00Z"));
        check("challenge marked", store.isChallengeUsed("CH-Z"));
        store.reset("C1");
        check("reset clears challenge ids", !store.isChallengeUsed("CH-Z"));

        // F7: padding-only Base32 rejected.
        check("Base32 '========' rejected", throwsIae(() -> Base32.decode("========")));

        // F8: Unicode digits rejected in cell parse.
        check("Cell.parse rejects Unicode digits", throwsIae(() -> Cell.parse("A\u0662")));

        // F9: dictionary rejects whitespace and case-insensitive duplicates.
        java.util.List<String> base = new java.util.ArrayList<>(WordDictionary.bip39English().words().subList(0, 256));
        java.util.List<String> withSpace = new java.util.ArrayList<>(base);
        withSpace.set(0, "a b");
        check("dictionary rejects whitespace word", throwsIae(() -> WordDictionary.of(withSpace)));
        java.util.List<String> caseDup = new java.util.ArrayList<>(base);
        caseDup.set(0, "Apple");
        caseDup.set(1, "apple");
        check("dictionary rejects case-insensitive duplicate", throwsIae(() -> WordDictionary.of(caseDup)));

        // F10: version validation.
        check("empty version rejected", throwsIae(() -> CotpScheme.builder().version("").build()));
        check("version with '|' rejected", throwsIae(() -> CotpScheme.builder().version("a|b").build()));

        // Round 3: TIME_BASED + CELL conflict, cross-config TTL cap, Unicode blanks.
        eq("TIME_BASED defaults to CHALLENGE burn", BurnPolicy.CHALLENGE,
                CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build().burnPolicy());
        check("TIME_BASED + explicit CELL rejected", throwsIae(() -> CotpPolicy.builder()
                .challengeMode(ChallengeMode.TIME_BASED).burnPolicy(BurnPolicy.CELL).build()));

        CotpScheme smallWindow = CotpScheme.builder().timeWindowBack(1).timeStepSeconds(30).build();
        CotpCard capCard = new CotpCardGenerator(smallWindow).generate("CAP", CotpSecret.of(secret));
        Cotp capGen = new Cotp(CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpChallenge capped = capGen.challengeGenerator().timeBased(capCard, now);
        // coverage = (timeWindowBack + 1) * step = (1 + 1) * 30 = 60 s, capped by policy TTL (120 s)
        check("TIME_BASED ttl capped to card window coverage",
                !capped.expiresAt().isAfter(now.plusSeconds(60)));

        java.util.List<String> nbsp = new java.util.ArrayList<>(base);
        nbsp.set(0, "a\u00A0b");
        check("dictionary rejects NBSP word", throwsIae(() -> WordDictionary.of(nbsp)));
        java.util.List<String> zwsp = new java.util.ArrayList<>(base);
        zwsp.set(0, "a\u200Bb");
        check("dictionary rejects zero-width space word", throwsIae(() -> WordDictionary.of(zwsp)));
        check("version of NBSP rejected", throwsIae(() -> CotpScheme.builder().version("\u00A0").build()));
    }

    private static void p2Features() {
        section("P2 features (cooldown, TTL eviction, secret cipher)");
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");

        // --- BurnPolicy.COOLDOWN ---
        CotpScheme cdScheme = CotpScheme.defaults();
        CotpPolicy cdPolicy = CotpPolicy.builder()
                .burnPolicy(BurnPolicy.COOLDOWN)
                .cellCooldown(java.time.Duration.ofHours(1))
                .build();
        Cotp cotp = new Cotp(cdScheme, cdPolicy);
        CotpCard card = cotp.generateCard("CD");
        CotpChallenge first = cotp.challengeGenerator().random(card, t0, cotp.verifier().burnStore());
        eq("cooldown first use OK", VerificationResult.OK,
                cotp.verifier().verify(card, null, first, card.wordAt(first.cell()), null, t0));

        Cell cell = first.cell();
        Instant within = t0.plusSeconds(1800);
        CotpChallenge inWindow = new CotpChallenge(cotp.challengeGenerator().newChallengeId(), card.cardId(),
                cell, ChallengeMode.RANDOM, within, within.plusSeconds(120));
        eq("cell within cooldown -> CELL_ALREADY_USED", VerificationResult.CELL_ALREADY_USED,
                cotp.verifier().verify(card, null, inWindow, card.wordAt(cell), null, within));

        Instant after = t0.plusSeconds(7200);
        CotpChallenge afterWindow = new CotpChallenge(cotp.challengeGenerator().newChallengeId(), card.cardId(),
                cell, ChallengeMode.RANDOM, after, after.plusSeconds(120));
        eq("cell after cooldown -> OK", VerificationResult.OK,
                cotp.verifier().verify(card, null, afterWindow, card.wordAt(cell), null, after));

        InMemoryBurnStore cdStore = new InMemoryBurnStore();
        CotpCard cdCard = new CotpCardGenerator(cdScheme).generate("CD2");
        for (Cell c : cdScheme.cells()) {
            cdStore.recordCellUse(cdCard.cardId(), c.id(), t0);
        }
        CotpChallengeGenerator cdGen = new CotpChallengeGenerator(cdPolicy);
        boolean cooling = false;
        try {
            cdGen.random(cdCard, t0.plusSeconds(600), cdStore);
        } catch (CardExhaustedException e) {
            cooling = e.getMessage().contains("cooldown");
        }
        check("all cells cooling -> CardExhaustedException(cooldown)", cooling);
        boolean available = true;
        try {
            cdGen.random(cdCard, t0.plusSeconds(7200), cdStore);
        } catch (CardExhaustedException e) {
            available = false;
        }
        check("cells available after cooldown", available);

        // --- TTL eviction ---
        InMemoryBurnStore bs = new InMemoryBurnStore();
        bs.markChallengeUsed("C", "ch1", t0.plusSeconds(120));
        bs.markChallengeUsed("C", "ch2", t0.plusSeconds(3600));
        check("challenge marked", bs.isChallengeUsed("ch1"));
        bs.purge(t0.plusSeconds(200));
        check("expired challenge evicted", !bs.isChallengeUsed("ch1"));
        check("unexpired challenge kept", bs.isChallengeUsed("ch2"));

        InMemoryThrottleStore ts = new InMemoryThrottleStore();
        ts.incrementFailure("TH");
        check("throttle failure recorded", ts.failureCount("TH") == 1);
        // C1 regression: a recently-active failure counter must NOT be evicted as idle.
        ts.purge(Instant.now(), java.time.Duration.ofHours(1));
        check("recent throttle state not evicted", ts.failureCount("TH") == 1);
        // Idle state (no access within the window) IS evicted.
        ts.purge(Instant.now().plusSeconds(2 * 3600), java.time.Duration.ofHours(1));
        check("idle throttle state evicted", ts.failureCount("TH") == 0);

        // --- SecretCipher (AES-GCM) ---
        AesGcmSecretCipher cipher = AesGcmSecretCipher.generate();
        byte[] secret = repeat((byte) 0x42, 32);
        byte[] sealed = cipher.encrypt(secret);
        check("ciphertext differs from plaintext", !java.util.Arrays.equals(sealed, secret));
        check("decrypt round-trip", java.util.Arrays.equals(secret, cipher.decrypt(sealed)));
        String b64 = cipher.encryptToBase64(secret);
        check("base64 round-trip", java.util.Arrays.equals(secret, cipher.decryptFromBase64(b64)));
        boolean wrongKey = false;
        try {
            AesGcmSecretCipher.generate().decrypt(sealed);
        } catch (CotpException e) {
            wrongKey = true;
        }
        check("wrong key rejected", wrongKey);
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 0x01;
        boolean tamperDetected = false;
        try {
            cipher.decrypt(tampered);
        } catch (CotpException e) {
            tamperDetected = true;
        }
        check("tampered ciphertext rejected", tamperDetected);
    }

    private static void eventsAudit() {
        section("Event sink / audit (P1-A)");
        java.util.List<CotpEvent> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CotpPolicy policy = CotpPolicy.builder().maxFailures(3).build();
        Cotp cotp = new Cotp(CotpScheme.defaults(), policy, events::add);
        CotpCard card = cotp.generateCard("EVT");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore(), "kiosk_login");
        check("CHALLENGE_ISSUED emitted", hasEvent(events, CotpEventType.CHALLENGE_ISSUED));
        check("challenge carries context", "kiosk_login".equals(ch.context()));

        eq("verify OK", VerificationResult.OK,
                cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));
        check("VERIFY_SUCCEEDED emitted", hasEvent(events, CotpEventType.VERIFY_SUCCEEDED));
        check("CELL_BURNED emitted", hasEvent(events, CotpEventType.CELL_BURNED));
        boolean contextPropagated = events.stream().anyMatch(e ->
                e.type() == CotpEventType.VERIFY_SUCCEEDED && "kiosk_login".equals(e.context()));
        check("verify event propagated challenge context", contextPropagated);

        CotpChallenge reuse = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore(), "kiosk_login");
        cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now);
        check("CHALLENGE_REUSED_ATTEMPT emitted", hasEvent(events, CotpEventType.CHALLENGE_REUSED_ATTEMPT));

        // Lockout after maxFailures (3) wrong PINs.
        CotpCard lockCard = cotp.generateCard("LOCK");
        String pinHash = cotp.newPinHash("1234".toCharArray());
        CotpChallenge lch = cotp.challengeGenerator().random(lockCard, now, cotp.verifier().burnStore(), "stepup");
        for (int i = 0; i < 3; i++) {
            cotp.verifier().verify(lockCard, pinHash, lch, lockCard.wordAt(lch.cell()), "0000".toCharArray(), now);
        }
        check("THROTTLE_LOCKOUT emitted", hasEvent(events, CotpEventType.THROTTLE_LOCKOUT));

        int before = events.size();
        CotpCard failCard = cotp.generateCard("FAIL");
        CotpChallenge wch = cotp.challengeGenerator().random(failCard, now, cotp.verifier().burnStore(), "stepup");
        cotp.verifier().verify(failCard, null, wch, "definitely-not-a-word", null, now);
        check("VERIFY_FAILED emitted", events.stream().skip(before)
                .anyMatch(e -> e.type() == CotpEventType.VERIFY_FAILED
                        && e.result() == VerificationResult.INVALID_WORD));

        String dump = events.toString();
        check("no secret word leaked into events",
                !dump.contains("definitely-not-a-word") && !dump.contains("1234"));
    }

    private static boolean hasEvent(java.util.List<CotpEvent> events, CotpEventType type) {
        return events.stream().anyMatch(e -> e.type() == type);
    }

    private static void credentialRegistry() {
        section("Credential codec + scheme registry (P2-A)");
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        Instant expires = Instant.parse("2027-09-01T00:00:00Z");
        CotpScheme scheme = CotpScheme.defaults();
        CotpCard card = new CotpCardGenerator(scheme).generate("CRED-CARD", CotpSecret.of(repeat((byte) 0x5c, 32)),
                created, expires);

        CotpCredential credential = CotpCredential.fromCard(card);
        eq("credential schemeId", scheme.version(), credential.schemeId());
        eq("credential cardId", card.cardId(), credential.cardId());

        String encoded = CotpCredentialCodec.encode(credential);
        check("encoded has prefix", encoded.startsWith("COTP1|"));
        check("encoded contains secret in clear", encoded.contains(credential.secret().toBase32()));
        check("credential.toString hides secret", !credential.toString().contains(credential.secret().toBase32()));

        CotpCredential decoded = CotpCredentialCodec.decode(encoded);
        eq("decoded cardId", credential.cardId(), decoded.cardId());
        eq("decoded schemeId", credential.schemeId(), decoded.schemeId());
        eq("decoded secret (constant equality)", credential.secret(), decoded.secret());
        eq("decoded createdAt", created, decoded.createdAt());
        eq("decoded expiresAt", expires, decoded.expiresAt());

        SchemeRegistry registry = SchemeRegistry.defaults();
        eq("registry resolves default version", scheme, registry.resolve(scheme.version()));
        check("registry contains version", registry.contains(scheme.version()));
        boolean unknown = false;
        try {
            registry.resolve("COTP/nope");
        } catch (CotpException e) {
            unknown = true;
        }
        check("unknown version rejected", unknown);
        boolean conflict = false;
        try {
            registry.register(CotpScheme.builder().version(scheme.version()).timeStepSeconds(45).build());
        } catch (IllegalStateException e) {
            conflict = true;
        }
        check("conflicting version rejected", conflict);

        CotpCard regenerated = decoded.toCard(registry);
        eq("regenerated grid equals original", card.grid().toString(), regenerated.grid().toString());
        eq("regenerated expiresAt", card.expiresAt(), regenerated.expiresAt());

        // checksum tamper
        char last = encoded.charAt(encoded.length() - 1);
        String tampered = encoded.substring(0, encoded.length() - 1) + (last == '0' ? '1' : '0');
        boolean badChecksum = false;
        try {
            CotpCredentialCodec.decode(tampered);
        } catch (CotpException e) {
            badChecksum = true;
        }
        check("checksum tamper detected", badChecksum);

        // scheme version mismatch when regenerating
        boolean mismatch = false;
        try {
            decoded.toCard(CotpScheme.builder().version("COTP/v2").build());
        } catch (IllegalArgumentException e) {
            mismatch = true;
        }
        check("scheme version mismatch rejected", mismatch);
    }

    private static void typedExceptionsFacade() {
        section("P2-B/C/D: exceptions, client errors, facade + policy resolver");

        // P2-D: typed exceptions
        boolean configTyped = false;
        try {
            CotpScheme.builder().columns(java.util.List.of('A', 'A', 'B')).rows(java.util.List.of(1)).build();
        } catch (CotpConfigurationException e) {
            configTyped = e instanceof IllegalArgumentException;
        }
        check("CotpConfigurationException (and is IllegalArgumentException)", configTyped);
        boolean cryptoTyped = false;
        try {
            AesGcmSecretCipher.generate().decrypt(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        } catch (CotpCryptoException e) {
            cryptoTyped = e instanceof CotpException;
        }
        check("CotpCryptoException (and is CotpException)", cryptoTyped);

        // P2-C: anti-oracle client errors
        eq("OK -> NONE", ClientError.NONE, VerificationResult.OK.toClientError());
        check("NONE is success", ClientError.NONE.isSuccess());
        eq("INVALID_WORD -> AUTHENTICATION_FAILED", ClientError.AUTHENTICATION_FAILED,
                VerificationResult.INVALID_WORD.toClientError());
        eq("INVALID_PIN -> AUTHENTICATION_FAILED", ClientError.AUTHENTICATION_FAILED,
                VerificationResult.INVALID_PIN.toClientError());
        eq("IDENTITY_MISMATCH -> AUTHENTICATION_FAILED", ClientError.AUTHENTICATION_FAILED,
                VerificationResult.IDENTITY_MISMATCH.toClientError());
        eq("CHALLENGE_REUSED -> AUTHENTICATION_FAILED", ClientError.AUTHENTICATION_FAILED,
                VerificationResult.CHALLENGE_REUSED.toClientError());
        eq("THROTTLED -> TOO_MANY_ATTEMPTS", ClientError.TOO_MANY_ATTEMPTS,
                VerificationResult.THROTTLED.toClientError());
        eq("CHALLENGE_EXPIRED -> CHALLENGE_EXPIRED", ClientError.CHALLENGE_EXPIRED,
                VerificationResult.CHALLENGE_EXPIRED.toClientError());
        eq("CARD_EXPIRED -> CARD_EXPIRED", ClientError.CARD_EXPIRED,
                VerificationResult.CARD_EXPIRED.toClientError());
        eq("INVALID_REQUEST -> BAD_REQUEST", ClientError.BAD_REQUEST,
                VerificationResult.INVALID_REQUEST.toClientError());
        check("no factor leakage: word vs pin same client error",
                VerificationResult.INVALID_WORD.toClientError() == VerificationResult.INVALID_PIN.toClientError());

        // P2-B: policy resolver presets
        CotpPolicyResolver resolver = CotpPolicyResolver.defaults();
        eq("resolver kiosk_login -> COOLDOWN", BurnPolicy.COOLDOWN, resolver.resolve("kiosk_login").burnPolicy());
        eq("resolver stepup -> CHALLENGE", BurnPolicy.CHALLENGE, resolver.resolve("stepup").burnPolicy());
        eq("resolver service_desk -> CELL", BurnPolicy.CELL, resolver.resolve("service_desk").burnPolicy());
        eq("resolver unknown -> default CELL", BurnPolicy.CELL, resolver.resolve("nope").burnPolicy());
        CotpPolicy fixed = CotpPolicy.builder().burnPolicy(BurnPolicy.NONE).build();
        eq("fixed resolver", fixed, CotpPolicyResolver.fixed(fixed).resolve("whatever"));

        // P2-B: facade enroll / challenge(context) / authenticate
        Cotp cotp = new Cotp(CotpScheme.defaults(), resolver);
        CotpEnrollment enr = cotp.enroll("LC-CARD", "1234".toCharArray());
        check("enroll returns credential", enr.credential().cardId().equals("LC-CARD"));
        check("enroll encoded credential decodes", CotpCredentialCodec.decode(enr.credentialEncoded())
                .cardId().equals("LC-CARD"));
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = cotp.challenge(enr.card(), "kiosk_login", now);
        eq("challenge carries context", "kiosk_login", ch.context());
        String word = enr.card().wordAt(ch.cell());
        eq("authenticate OK", VerificationResult.OK,
                cotp.authenticate(enr.card(), enr.pinHash(), ch, word, "1234".toCharArray(), now));

        CotpChallenge ch2 = cotp.challenge(enr.card(), "kiosk_login", now.plusSeconds(1));
        VerificationResult wrong = cotp.authenticate(enr.card(), enr.pinHash(), ch2,
                enr.card().wordAt(ch2.cell()), "0000".toCharArray(), now.plusSeconds(1));
        eq("wrong PIN -> INVALID_PIN", VerificationResult.INVALID_PIN, wrong);
        eq("wrong PIN client error generic", ClientError.AUTHENTICATION_FAILED, wrong.toClientError());
    }

    private static void throttleFlow() {
        section("Throttling / lockout");
        CotpScheme config = CotpScheme.defaults(); // maxFailures = 5
        Cotp cotp = new Cotp(config);
        byte[] secret = repeat((byte) 0x33, 32);
        CotpCard card = cotp.generateCard("THR-CARD", CotpSecret.of(secret));
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        for (int i = 0; i < 5; i++) {
            cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()), "0000".toCharArray(), now);
        }
        eq("after 5 failures -> THROTTLED", VerificationResult.THROTTLED,
                cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()), "1234".toCharArray(), now));

        Instant later = now.plusSeconds(16 * 60); // lock (15 min) elapsed
        CotpChallenge fresh = cotp.challengeGenerator().random(card, later, cotp.verifier().burnStore());
        eq("after lock expires -> OK", VerificationResult.OK,
                cotp.verifier().verify(card, pinHash, fresh, card.wordAt(fresh.cell()), "1234".toCharArray(), later));
    }

    private static void timeBasedFlow() {
        section("Time-based challenge mode");
        CotpScheme config = CotpScheme.defaults();
        Cotp cotp = new Cotp(config, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard card = cotp.generateCard("TIME-CARD");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge a = cotp.challengeGenerator().timeBased(card, now);
        CotpChallenge b = cotp.challengeGenerator().timeBased(card, now);
        eq("same instant -> same cell", a.cellId(), b.cellId());

        CotpChallenge c = cotp.challengeGenerator().timeBased(card, now.plusSeconds(30));
        check("next step is a valid slot", config.cells().contains(c.cell()));

        eq("time-based verify OK", VerificationResult.OK,
                cotp.verifier().verify(card, null, a, card.wordAt(a.cell()), null, now));

        // C4 regression: timeWindowBack=0 must still yield a usable (non-immediately-expired) TTL.
        CotpScheme zeroBack = CotpScheme.builder().timeWindowBack(0).build();
        Cotp zero = new Cotp(zeroBack, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard zc = zero.generateCard("ZERO-BACK");
        CotpChallenge zch = zero.challengeGenerator().timeBased(zc, now);
        check("timeWindowBack=0 challenge not expired", zch.expiresAt().isAfter(now));
        eq("timeWindowBack=0 verify OK", VerificationResult.OK,
                zero.verifier().verify(zc, null, zch, zc.wordAt(zch.cell()), null, now));
    }

    private static void distributionSanity() {
        section("Uniformity sanity (word index distribution)");
        CotpScheme config = CotpScheme.builder().build();
        Map<String, Integer> buckets = new HashMap<>();
        buckets.put("0", 0);
        buckets.put("1", 0);
        buckets.put("2", 0);
        buckets.put("3", 0);
        byte[] secret = new byte[32];
        SecureRandom rng = new SecureRandom();
        int samples = 8000;
        for (int i = 0; i < samples; i++) {
            rng.nextBytes(secret);
            int idx = CotpDerivation.wordIndex(CotpDerivation.gridKey(secret, "D", config),
                    Cell.parse("A1"), 0, config);
            buckets.merge(String.valueOf(idx / 512), 1, Integer::sum);
        }
        int expected = samples / 4;
        boolean ok = true;
        for (int v : buckets.values()) {
            if (Math.abs(v - expected) > expected * 0.15) {
                ok = false;
            }
        }
        check("index buckets within 15% of uniform (n=" + samples + ")", ok);
        System.out.println("   buckets=" + buckets);
    }

    private static void cellParsing() {
        section("Cell parsing / canonical ids");
        eq("cell id single", "C2", new Cell('c', 2, CellPosition.SINGLE).id());
        eq("cell id top", "C2T", new Cell('C', 2, CellPosition.TOP).id());
        eq("cell parse single", new Cell('C', 2, CellPosition.SINGLE), Cell.parse("c2"));
        eq("cell parse top", new Cell('C', 2, CellPosition.TOP), Cell.parse("C2T"));
        eq("cell parse bottom", new Cell('E', 5, CellPosition.BOTTOM), Cell.parse("E5B"));
        check("cell parse rejects explicit SINGLE token", throwsIae(() -> Cell.parse("A1S")));
    }

    // ------------------------------------------------------------------ helpers

    private static void section(String name) {
        System.out.println();
        System.out.println("[ " + name + " ]");
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name + " = " + actual);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + " expected=[" + expected + "] actual=[" + actual + "]");
        }
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, b);
        return out;
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
