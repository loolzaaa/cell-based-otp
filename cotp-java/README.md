# COTP Java — Cell-based One-Time Password

Zero-dependency Java library for **COTP**: a deterministic, printable 5×5 challenge-card
authentication factor, built as an analog of the *Avatier Identity Challenge Card*.

The server stores **only the card secret**. The entire printed word grid is regenerated on demand
from the secret via HKDF (RFC 5869) and HMAC-SHA-256. Nothing else needs to be persisted.

- Full specification: [`../COTP_SPECIFICATION.md`](../COTP_SPECIFICATION.md)
- Integration guide + runnable example: [`../INTEGRATION_GUIDE.md`](../INTEGRATION_GUIDE.md),
  [`../examples/IntegrationExample.java`](../examples/IntegrationExample.java)
- Security & ops: [`../docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md),
  [`../docs/OPERATIONS_RUNBOOK.md`](../docs/OPERATIONS_RUNBOOK.md)

## Design at a glance

```
card_secret (256-bit CSPRNG)
        │  HKDF-Extract(salt = cardId)
        ▼
       PRK ── HKDF-Expand(info="COTP/v1/grid") ──▶ K_grid
                                                        │  HMAC-SHA-256(K_grid, "COTP/v1/word|C2|0")
                                                        ▼
                                         11 low bits ──▶ index 0..2047 ──▶ dictionary word
```

* Dictionary size MUST be a power of two (bundled BIP-39 English = 2048 = 2¹¹ ⇒ 11 bits/word,
  **no modulo bias**).
* Grid is assembled in canonical order; duplicate words are resolved deterministically by
  incrementing a nonce.
* Challenges are either random (CSPRNG) or time-based (HMAC), with TTL.
* One-time semantics (`BurnPolicy`) and PIN throttling/lockout are enforced server-side.

## Requirements

* JDK 17+ (compiled/tested with Temurin 21)
* Runtime dependencies: **none** (only `javax.crypto.Mac` / `java.security`)
* Tests: JUnit 5 (test scope only)

## Quick start

```java
Cotp cotp = new Cotp();                              // 5x5, BIP-39, HMAC-SHA-256

// Enrolment (server)
CotpCard card = cotp.generateCard(cotp.newCardId()); // card.secret() + card.grid()
String pinHash = cotp.newPinHash("1234".toCharArray());
// persist: cardId, card.secret(), pinHash — print card.grid() for the user

// Login / step-up
Instant now = Instant.now();
CotpChallenge ch = cotp.challengeGenerator().newChallenge(card, now, cotp.verifier().burnStore());
// show ch.cellId() to the user
VerificationResult r = cotp.verifier().verify(
        card, pinHash, ch, userWord, userPin, now);
if (r.isSuccess()) { /* issue step-up token */ }
```

## Build & verify

Maven Wrapper (JDK 17+; no Maven install required):

```bash
./mvnw -f pom.xml verify        # Linux/macOS
```

```powershell
.\mvnw.cmd -f pom.xml verify    # Windows
```

Without Maven at all, use the bundled script (this is how the repo is verified here):

```powershell
./run-verify.ps1
```

It compiles `src/main/java` + `tools/`, copies resources and runs the dependency-free
`com.cotp.CotpSelfTest` (178 assertions, including the full reference vector).

## Layout

```
cotp-java/
├─ pom.xml
├─ mvnw / mvnw.cmd              Maven Wrapper
├─ run-verify.ps1              build + self-test without Maven
├─ src/main/java/module-info.java                 JPMS descriptor (module com.cotp)
├─ src/main/java/com/cotp/                        library
├─ src/main/resources/com/cotp/cotp-english.txt   BIP-39 dictionary (2048 words)
├─ src/test/java/com/cotp/                        JUnit 5 tests
└─ tools/                       standalone vector printer + self-test
```

## License

MIT.
