package com.cotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the COTP library. Determinism is validated against a fixed reference vector,
 * and HKDF is validated against RFC 5869 test case 1.
 */
class CotpTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] REF_SECRET =
            HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final String REF_CARD_ID = "COTP-DEMO-0001";

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.extract(salt, ikm);
        byte[] okm = Hkdf.expand(prk, info, 42);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HEX.formatHex(prk));
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(okm));
    }

    @Test
    void gridIsDeterministic() {
        CotpScheme config = CotpScheme.defaults();
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 100; i++) {
            byte[] secret = new byte[32];
            rng.nextBytes(secret);
            Map<String, String> a = CotpDerivation.buildGrid(CotpDerivation.gridKey(secret, "C" + i, config), config);
            Map<String, String> b = CotpDerivation.buildGrid(CotpDerivation.gridKey(secret.clone(), "C" + i, config), config);
            assertEquals(a, b);
            assertEquals(a.size(), new HashSet<>(a.values()).size(), "words must be unique within a card");
        }
    }

    @Test
    void referenceVectorIsStable() {
        CotpScheme config = CotpScheme.defaults();
        byte[] gridKey = CotpDerivation.gridKey(REF_SECRET, REF_CARD_ID, config);
        assertEquals("e8411322b623d405241e8d93ecc1bbdd8f2048f57e3c0dd342fc1041b661d9fd", HEX.formatHex(gridKey));
        assertEquals(875, CotpDerivation.wordIndex(gridKey, Cell.parse("A1"), 0, config));
        assertEquals(3, CotpDerivation.wordIndex(gridKey, Cell.parse("C2"), 0, config));
        assertEquals(300, CotpDerivation.wordIndex(gridKey, Cell.parse("E5"), 0, config));

        Map<String, String> grid = CotpDerivation.buildGrid(gridKey, config);
        assertEquals("hope", grid.get("A1"));
        assertEquals("about", grid.get("C2"));
        assertEquals("cereal", grid.get("E5"));
    }

    @Test
    void oneBitSecretChangeAltersGrid() {
        CotpScheme config = CotpScheme.defaults();
        byte[] s1 = new byte[32];
        byte[] s2 = new byte[32];
        s2[0] = 1;
        Map<String, String> g1 = CotpDerivation.buildGrid(CotpDerivation.gridKey(s1, "X", config), config);
        Map<String, String> g2 = CotpDerivation.buildGrid(CotpDerivation.gridKey(s2, "X", config), config);
        assertNotEquals(g1, g2);
    }

    @Test
    void verificationHappyPathAndFailures() {
        Cotp cotp = new Cotp();
        CotpCard card = cotp.generateCard(REF_CARD_ID, CotpSecret.of(REF_SECRET));
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()), "1234".toCharArray(), now));

        CotpChallenge wrongWord = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        assertEquals(VerificationResult.INVALID_WORD,
                cotp.verifier().verify(card, pinHash, wrongWord, "nonsense", "1234".toCharArray(), now));

        CotpChallenge wrongPin = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        assertEquals(VerificationResult.INVALID_PIN,
                cotp.verifier().verify(card, pinHash, wrongPin, card.wordAt(wrongPin.cell()), "0000".toCharArray(), now));
    }

    @Test
    void burnAndReplaySemantics() {
        Cotp cotp = new Cotp();
        CotpCard card = cotp.generateCard("BURN", CotpSecret.of(REF_SECRET));
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());

        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));
        assertEquals(VerificationResult.CHALLENGE_REUSED,
                cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));
    }

    @Test
    void timeBasedChallengeIsStableWithinStep() {
        Cotp cotp = new Cotp(CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard card = cotp.generateCard("TIME", CotpSecret.of(REF_SECRET));
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        assertEquals(cotp.challengeGenerator().timeBased(card, now).cellId(),
                cotp.challengeGenerator().timeBased(card, now).cellId());
    }

    @Test
    void base32RoundTrip() {
        byte[] data = {0x00, 0x01, 0x02, (byte) 0xff, 0x10};
        assertTrue(java.util.Arrays.equals(data, Base32.decode(Base32.encode(data))));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("!!!!"));
    }

    @Test
    void pinHasherRoundTrip() {
        PinHasher hasher = new PinHasher(10_000);
        String hash = hasher.hash("1234".toCharArray());
        assertTrue(hasher.verify("1234".toCharArray(), hash));
        assertFalse(hasher.verify("9999".toCharArray(), hash));
        assertFalse(hasher.verify("1234".toCharArray(), null));
    }

    @Test
    void dictionaryMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> WordDictionary.of(java.util.List.of("a", "b", "c")));
    }

    @Test
    void cellParsing() {
        assertEquals(new Cell('C', 2, CellPosition.SINGLE), Cell.parse("c2"));
        assertEquals(new Cell('C', 2, CellPosition.TOP), Cell.parse("C2T"));
        assertEquals("E5B", new Cell('E', 5, CellPosition.BOTTOM).id());
        assertThrows(IllegalArgumentException.class, () -> Cell.parse("A1S"));
    }

    // ---- Regression tests for defects found in the audit ----

    @Test
    void gridIsInCanonicalOrder() {
        CotpScheme config = CotpScheme.defaults();
        CotpCard card = new CotpCardGenerator(config).generate("ORDER", CotpSecret.of(REF_SECRET));
        List<String> expected = config.cells().stream().map(Cell::id).toList();
        assertEquals(expected, new ArrayList<>(card.grid().keySet()));
    }

    @Test
    void forgedTimeBasedCellIsRejected() {
        CotpScheme config = CotpScheme.defaults();
        Cotp cotp = new Cotp(config, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard card = cotp.generateCard("TB");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        java.util.Set<String> windowCells = new java.util.HashSet<>();
        for (int d = -config.timeWindowBack(); d <= config.timeWindowForward(); d++) {
            windowCells.add(cotp.challengeGenerator().timeBased(card, now, d).cellId());
        }
        Cell other = config.cells().stream()
                .filter(c -> !windowCells.contains(c.id())).findFirst().orElseThrow();
        CotpChallenge forged = new CotpChallenge("FORGED", card.cardId(), other,
                ChallengeMode.TIME_BASED, now, now.plusSeconds(120));
        assertEquals(VerificationResult.INVALID_REQUEST,
                cotp.verifier().verify(card, null, forged, card.wordAt(other), null, now));
    }

    @Test
    void configurationValidation() {
        assertThrows(IllegalArgumentException.class, () -> CotpScheme.builder()
                .columns(List.of('A', 'A', 'B')).rows(List.of(1)).build());
        assertThrows(IllegalArgumentException.class, () -> CotpPolicy.builder()
                .challengeTtl(Duration.ofSeconds(-1)).build());
        List<String> small = WordDictionary.bip39English().words().subList(0, 256);
        assertThrows(IllegalArgumentException.class, () -> CotpScheme.builder()
                .dictionary(WordDictionary.of(small))
                .columns(IntStream.rangeClosed('A', 'Z').mapToObj(c -> (char) c).toList())
                .rows(IntStream.rangeClosed(1, 10).boxed().toList())
                .build());
    }

    @Test
    void base32Strictness() {
        assertArrayEquals(new byte[]{0x66}, Base32.decode("MY"));
        assertTrue(java.util.Arrays.equals("foobar".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base32.decode("MZXW6YTBOI")));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("A"));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("AB"));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("A2="));
    }

    @Test
    void unknownCellIsInvalidRequest() {
        Cotp cotp = new Cotp();
        CotpCard card = cotp.generateCard("CELL");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge bad = new CotpChallenge("X", card.cardId(),
                new Cell('Z', 9, CellPosition.SINGLE), ChallengeMode.RANDOM, now, now.plusSeconds(120));
        assertEquals(VerificationResult.INVALID_REQUEST,
                cotp.verifier().verify(card, null, bad, "whatever", null, now));
    }

    @Test
    void cardConfigUsedForVerification() {
        CotpScheme v2 = CotpScheme.builder().version("COTP/v2").build();
        CotpCard card = new CotpCardGenerator(v2).generate("V2", CotpSecret.of(REF_SECRET));
        Cotp cotp = new Cotp();
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));
    }

    // ---- Regression tests for defects found in the second audit ----

    private static java.util.Set<String> windowCells(Cotp cotp, CotpCard card, CotpScheme cfg, Instant ref) {
        java.util.Set<String> ids = new HashSet<>();
        for (int d = -cfg.timeWindowBack(); d <= cfg.timeWindowForward(); d++) {
            ids.add(cotp.challengeGenerator().timeBased(card, ref, d).cellId());
        }
        return ids;
    }

    @Test
    void challengeGenerationUsesCardLayout() {
        CotpScheme paired = CotpScheme.builder()
                .positions(List.of(CellPosition.TOP, CellPosition.BOTTOM)).build();
        CotpCard card = new Cotp(paired).generateCard("PAIRS");
        Cotp def = new Cotp(); // SINGLE layout != card layout
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = def.challengeGenerator().random(card, now, def.verifier().burnStore());
        assertTrue(paired.cells().contains(ch.cell()));
        assertEquals(VerificationResult.OK,
                def.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));
    }

    @Test
    void timeBasedWindowIsAnchoredOnNow() {
        CotpScheme cfg = CotpScheme.defaults();
        Cotp cotp = new Cotp(cfg, CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        CotpCard card = cotp.generateCard("ANCHOR");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        Instant issued = now.minusSeconds(6L * cfg.timeStepSeconds());
        java.util.Set<String> nowWin = windowCells(cotp, card, cfg, now);
        java.util.Set<String> issuedWin = windowCells(cotp, card, cfg, issued);
        Cell target = cfg.cells().stream()
                .filter(c -> issuedWin.contains(c.id()) && !nowWin.contains(c.id()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(target != null);
        CotpChallenge stale = new CotpChallenge("STALE", card.cardId(), target,
                ChallengeMode.TIME_BASED, issued, now.plusSeconds(100_000));
        assertEquals(VerificationResult.INVALID_REQUEST,
                cotp.verifier().verify(card, null, stale, card.wordAt(target), null, now));
    }

    @Test
    void cardExhaustionIsSignalled() {
        Cotp cotp = new Cotp();
        CotpCard card = cotp.generateCard("EXH");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge pre = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        for (Cell c : CotpScheme.defaults().cells()) {
            cotp.verifier().burnStore().markCellBurned(card.cardId(), c.id());
        }
        assertEquals(VerificationResult.CARD_EXHAUSTED,
                cotp.verifier().verify(card, null, pre, card.wordAt(pre.cell()), null, now));
        assertThrows(CardExhaustedException.class,
                () -> cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore()));
    }

    @Test
    void deriveUsesCardConfig() {
        CotpScheme v2 = CotpScheme.builder().version("COTP/v2").build();
        CotpCard card = new CotpCardGenerator(v2).generate("V2D", CotpSecret.of(REF_SECRET));
        CotpCard derived = new CotpCardGenerator(CotpScheme.defaults()).derive(card);
        assertEquals(card.grid(), derived.grid());
    }

    @Test
    void identityMismatchDoesNotThrottle() {
        Cotp cotp = new Cotp();
        CotpCard card = cotp.generateCard("ID");
        String pinHash = cotp.newPinHash("1234".toCharArray());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        for (int i = 0; i < 3; i++) {
            CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
            assertEquals(VerificationResult.IDENTITY_MISMATCH,
                    cotp.verifier().verify(card, pinHash, ch, card.wordAt(ch.cell()),
                            "1234".toCharArray(), "WRONG", "RIGHT", now));
        }
        CotpChallenge ok = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore());
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, pinHash, ok, card.wordAt(ok.cell()),
                        "1234".toCharArray(), "RIGHT", "RIGHT", now));
    }

    @Test
    void resetClearsChallengeIds() {
        InMemoryBurnStore store = new InMemoryBurnStore();
        store.markChallengeUsed("C1", "CH-Z", Instant.parse("2026-10-06T13:00:00Z"));
        assertTrue(store.isChallengeUsed("CH-Z"));
        store.reset("C1");
        assertFalse(store.isChallengeUsed("CH-Z"));
    }

    @Test
    void base32PaddingOnlyRejected() {
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("========"));
    }

    @Test
    void cellRejectsUnicodeDigits() {
        assertThrows(IllegalArgumentException.class, () -> Cell.parse("A\u0662"));
    }

    @Test
    void dictionaryRejectsWhitespaceAndCaseDuplicates() {
        List<String> base = new ArrayList<>(WordDictionary.bip39English().words().subList(0, 256));
        List<String> withSpace = new ArrayList<>(base);
        withSpace.set(0, "a b");
        assertThrows(IllegalArgumentException.class, () -> WordDictionary.of(withSpace));
        List<String> caseDup = new ArrayList<>(base);
        caseDup.set(0, "Apple");
        caseDup.set(1, "apple");
        assertThrows(IllegalArgumentException.class, () -> WordDictionary.of(caseDup));
    }

    @Test
    void versionValidation() {
        assertThrows(IllegalArgumentException.class, () -> CotpScheme.builder().version("").build());
        assertThrows(IllegalArgumentException.class, () -> CotpScheme.builder().version("a|b").build());
        assertThrows(IllegalArgumentException.class, () -> CotpScheme.builder().version("\u00A0").build());
    }

    // ---- Regression tests for the round-2 targeted audit ----

    @Test
    void timeBasedDefaultsToChallengeBurn() {
        CotpPolicy cfg = CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build();
        assertEquals(BurnPolicy.CHALLENGE, cfg.burnPolicy());
        assertThrows(IllegalArgumentException.class, () -> CotpPolicy.builder()
                .challengeMode(ChallengeMode.TIME_BASED).burnPolicy(BurnPolicy.CELL).build());
    }

    @Test
    void timeBasedTtlCappedToCardWindowCoverage() {
        CotpScheme smallWindow = CotpScheme.builder().timeWindowBack(1).timeStepSeconds(30).build();
        CotpCard card = new CotpCardGenerator(smallWindow).generate("CAP", CotpSecret.of(REF_SECRET));
        Cotp capGen = new Cotp(CotpPolicy.builder().challengeMode(ChallengeMode.TIME_BASED).build());
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = capGen.challengeGenerator().timeBased(card, now);
        assertTrue(!ch.expiresAt().isAfter(now.plusSeconds(30)));
    }

    @Test
    void dictionaryRejectsUnicodeBlankWords() {
        List<String> base = new ArrayList<>(WordDictionary.bip39English().words().subList(0, 256));
        List<String> nbsp = new ArrayList<>(base);
        nbsp.set(0, "a\u00A0b");
        assertThrows(IllegalArgumentException.class, () -> WordDictionary.of(nbsp));
        List<String> zwsp = new ArrayList<>(base);
        zwsp.set(0, "a\u200Bb");
        assertThrows(IllegalArgumentException.class, () -> WordDictionary.of(zwsp));
    }

    // ---- P2 features ----

    @Test
    void cooldownPolicy() {
        Cotp cotp = new Cotp(CotpPolicy.builder()
                .burnPolicy(BurnPolicy.COOLDOWN).cellCooldown(Duration.ofHours(1)).build());
        CotpCard card = cotp.generateCard("CD");
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge first = cotp.challengeGenerator().random(card, t0, cotp.verifier().burnStore());
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, null, first, card.wordAt(first.cell()), null, t0));

        Cell cell = first.cell();
        Instant within = t0.plusSeconds(1800);
        CotpChallenge inWindow = new CotpChallenge("x", card.cardId(), cell,
                ChallengeMode.RANDOM, within, within.plusSeconds(120));
        assertEquals(VerificationResult.CELL_ALREADY_USED,
                cotp.verifier().verify(card, null, inWindow, card.wordAt(cell), null, within));

        Instant after = t0.plusSeconds(7200);
        CotpChallenge afterWindow = new CotpChallenge("y", card.cardId(), cell,
                ChallengeMode.RANDOM, after, after.plusSeconds(120));
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, null, afterWindow, card.wordAt(cell), null, after));
    }

    @Test
    void ttlEviction() {
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        InMemoryBurnStore bs = new InMemoryBurnStore();
        bs.markChallengeUsed("C", "ch1", t0.plusSeconds(120));
        bs.markChallengeUsed("C", "ch2", t0.plusSeconds(3600));
        bs.purge(t0.plusSeconds(200));
        assertFalse(bs.isChallengeUsed("ch1"));
        assertTrue(bs.isChallengeUsed("ch2"));

        InMemoryThrottleStore ts = new InMemoryThrottleStore();
        ts.incrementFailure("TH");
        assertEquals(1, ts.failureCount("TH"));
        ts.purge(t0.plusSeconds(10), Duration.ofHours(1));
        assertEquals(0, ts.failureCount("TH"));
    }

    @Test
    void credentialCodecRoundTripAndChecksum() {
        CotpScheme scheme = CotpScheme.defaults();
        CotpCard card = new CotpCardGenerator(scheme).generate("CRED", CotpSecret.of(REF_SECRET),
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2027-09-01T00:00:00Z"));
        CotpCredential cred = CotpCredential.fromCard(card);
        String encoded = CotpCredentialCodec.encode(cred);
        CotpCredential decoded = CotpCredentialCodec.decode(encoded);
        assertEquals(cred.cardId(), decoded.cardId());
        assertEquals(cred.schemeId(), decoded.schemeId());
        assertEquals(cred.secret(), decoded.secret());
        assertEquals(cred.createdAt(), decoded.createdAt());
        assertEquals(cred.expiresAt(), decoded.expiresAt());
        assertFalse(cred.toString().contains(cred.secret().toBase32()));

        char last = encoded.charAt(encoded.length() - 1);
        String tampered = encoded.substring(0, encoded.length() - 1) + (last == '0' ? '1' : '0');
        assertThrows(CotpException.class, () -> CotpCredentialCodec.decode(tampered));
    }

    @Test
    void schemeRegistryResolution() {
        SchemeRegistry registry = SchemeRegistry.defaults();
        assertEquals(CotpScheme.defaults(), registry.resolve(CotpScheme.DEFAULT_VERSION));
        assertThrows(CotpException.class, () -> registry.resolve("COTP/unknown"));
        assertThrows(IllegalStateException.class, () -> registry.register(
                CotpScheme.builder().version(CotpScheme.DEFAULT_VERSION).timeStepSeconds(45).build()));
    }

    @Test
    void credentialRegeneratesCard() {
        CotpScheme scheme = CotpScheme.builder().version("COTP/v2").build();
        CotpCard card = new CotpCardGenerator(scheme).generate("REGEN", CotpSecret.of(REF_SECRET));
        CotpCredential cred = CotpCredential.fromCard(card);
        SchemeRegistry registry = SchemeRegistry.of(scheme);
        assertEquals(card.grid(), cred.toCard(registry).grid());
    }

    @Test
    void typedExceptions() {
        assertThrows(CotpConfigurationException.class, () -> CotpScheme.builder()
                .columns(List.of('A', 'A', 'B')).rows(List.of(1)).build());
        assertThrows(CotpCryptoException.class, () -> AesGcmSecretCipher.generate()
                .decrypt(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}));
    }

    @Test
    void clientErrorIsAntiOracle() {
        assertEquals(ClientError.NONE, VerificationResult.OK.toClientError());
        assertTrue(ClientError.NONE.isSuccess());
        assertEquals(ClientError.AUTHENTICATION_FAILED, VerificationResult.INVALID_WORD.toClientError());
        assertEquals(VerificationResult.INVALID_WORD.toClientError(),
                VerificationResult.INVALID_PIN.toClientError(),
                "word and PIN failures must be indistinguishable to clients");
        assertEquals(ClientError.TOO_MANY_ATTEMPTS, VerificationResult.THROTTLED.toClientError());
        assertEquals(ClientError.CHALLENGE_EXPIRED, VerificationResult.CHALLENGE_EXPIRED.toClientError());
        assertEquals(ClientError.CARD_EXPIRED, VerificationResult.CARD_EXPIRED.toClientError());
        assertEquals(ClientError.BAD_REQUEST, VerificationResult.INVALID_REQUEST.toClientError());
    }

    @Test
    void facadeEnrollChallengeAuthenticateWithResolver() {
        CotpPolicyResolver resolver = CotpPolicyResolver.defaults();
        assertEquals(BurnPolicy.COOLDOWN, resolver.resolve("kiosk_login").burnPolicy());
        assertEquals(BurnPolicy.CHALLENGE, resolver.resolve("stepup").burnPolicy());
        assertEquals(BurnPolicy.CELL, resolver.resolve("service_desk").burnPolicy());

        Cotp cotp = new Cotp(CotpScheme.defaults(), resolver);
        CotpEnrollment enr = cotp.enroll("LC", "1234".toCharArray());
        assertEquals("LC", enr.card().cardId());
        assertEquals("LC", CotpCredentialCodec.decode(enr.credentialEncoded()).cardId());

        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = cotp.challenge(enr.card(), "kiosk_login", now);
        assertEquals("kiosk_login", ch.context());
        assertEquals(VerificationResult.OK, cotp.authenticate(enr.card(), enr.pinHash(), ch,
                enr.card().wordAt(ch.cell()), "1234".toCharArray(), now));
    }

    @Test
    void eventSinkReceivesAuditEvents() {
        List<CotpEvent> events = new ArrayList<>();
        Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), events::add);
        CotpCard card = cotp.generateCard("EVT");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        CotpChallenge ch = cotp.challengeGenerator().random(card, now, cotp.verifier().burnStore(), "kiosk_login");
        assertEquals(VerificationResult.OK,
                cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now));

        assertTrue(events.stream().anyMatch(e ->
                e.type() == CotpEventType.CHALLENGE_ISSUED && "kiosk_login".equals(e.context())));
        assertTrue(events.stream().anyMatch(e ->
                e.type() == CotpEventType.VERIFY_SUCCEEDED && "kiosk_login".equals(e.context())));
        assertTrue(events.stream().anyMatch(e -> e.type() == CotpEventType.CELL_BURNED));

        cotp.verifier().verify(card, null, ch, card.wordAt(ch.cell()), null, now);
        assertTrue(events.stream().anyMatch(e -> e.type() == CotpEventType.CHALLENGE_REUSED_ATTEMPT));
    }

    @Test
    void secretCipherRoundTripAndTamper() {
        AesGcmSecretCipher cipher = AesGcmSecretCipher.generate();
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 0x42);
        byte[] sealed = cipher.encrypt(secret);
        assertArrayEquals(secret, cipher.decrypt(sealed));
        assertArrayEquals(secret, cipher.decryptFromBase64(cipher.encryptToBase64(secret)));
        assertThrows(CotpException.class, () -> AesGcmSecretCipher.generate().decrypt(sealed));
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(CotpException.class, () -> cipher.decrypt(tampered));
    }
}
