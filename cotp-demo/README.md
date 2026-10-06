# COTP web demo

A self-contained, dependency-free web demo of the COTP Java library. It compiles the **real**
core library from `cotp-java/src/main/java` and drives it through a small JSON/REST API and a
single static `index.html`, so you can watch the whole enrolment → challenge → verify lifecycle
(and the burn/cooldown/throttle policies) end to end.

- Backend: **JDK only** — `com.sun.net.httpserver.HttpServer`. No Spring, Javalin, Maven or Gradle.
- Frontend: one static `web/index.html` (inline CSS/JS) served by the same server.
- Crypto: the actual `com.cotp` classes — nothing is reimplemented.

## Requirements

- A JDK 21+ (`java` and `javac` on `PATH`). Check with `java -version`.

## Run

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\cotp-demo\run-demo.ps1
```

Then open the URL it prints, e.g. **http://localhost:8080**.

Optional parameters:

```powershell
# choose another port
powershell -ExecutionPolicy Bypass -File .\cotp-demo\run-demo.ps1 -Port 9090
```

The script:

1. compiles `cotp-java/src/main/java` + `cotp-demo/src` with `javac -encoding UTF-8` into
   `cotp-demo/build/classes` (a distinct build dir — `cotp-java/build` is never touched);
2. copies the bundled dictionary to `cotp-demo/build/classes/com/cotp/cotp-english.txt`
   and the frontend to `cotp-demo/build/classes/web/index.html`;
3. starts the server and prints the listening URL.

Stop it with `Ctrl+C`. On Windows, if script execution is disabled, use the
`-ExecutionPolicy Bypass` flag shown above.

## What to click (guided walkthrough)

1. **Enrolment.** Leave the defaults (SINGLE, CELL, RANDOM, PIN `1234`) and click
   **Enrol / regenerate card**. You get a `card_id`, the printed 5×5 grid, and the exact
   `CotpCardPrinter.render(...)` text. The derived PIN hash is **redacted** in the UI. Switch the layout to **WORD_PAIRS** and
   re-enrol to see TOP/BOTTOM word pairs in every cell.
2. **Challenge.** Click **Issue challenge**. The requested cell is shown large, with a live TTL
   timer. In TIME_BASED mode the same time step always yields the same cell.
3. **Verify.** Use **Auto-fill word from challenged cell** (or read the highlighted grid cell
   yourself), type the PIN and press **Verify**. The `VerificationResult` is shown as a badge.
4. **Policy playground.**
   - `BurnPolicy=CELL`: verify a cell successfully, then press **Re-verify last challenge** to see
     `CHALLENGE_REUSED`; force the *same cell* again with the **Force this cell** box and verify to
     see `CELL_ALREADY_USED`.
   - `BurnPolicy=COOLDOWN`: a used cell returns `CELL_ALREADY_USED` until the cooldown window
     elapses; the state panel counts down the remaining cooldown.
   - `BurnPolicy=CHALLENGE`: the same cell may be reused immediately, but a consumed
     `challenge_id` still returns `CHALLENGE_REUSED`.
   - **Throttling**: submit a wrong PIN repeatedly. After `Max failures` bad attempts the card is
     locked for `Lockout` seconds and the next attempt returns `THROTTLED`.
   - `ChallengeMode=TIME_BASED`: watch deterministic, stateless cell selection. It cannot be
     combined with CELL/COOLDOWN burn (the library rejects that combination).
5. **Audit log.** The bottom-right panel lists every server-side event and state change. It only
   contains outcomes/metadata — the submitted word and PIN are never logged.

## REST API (used by the frontend)

| Method | Path             | Body (JSON)                                                        | Returns |
|--------|------------------|--------------------------------------------------------------------|---------|
| GET    | `/api/state`     | –                                                                  | full session state + audit log |
| POST   | `/api/enroll`    | `{layout,pin,burnPolicy,challengeMode,ttlSeconds,cooldownSeconds,maxFailures,lockSeconds,pinIterations}` | card grid, `card_id`, state (PIN hash redacted) |
| POST   | `/api/challenge` | `{}` or `{"cellId":"C2"}`                                          | new challenge + state |
| POST   | `/api/verify`    | `{"challengeId":"…","word":"…","pin":"…"}`                         | `VerificationResult` + state |
| POST   | `/api/reset`     | `{}`                                                               | cleared state |

All responses are JSON. Errors return a non-200 status and `{"error":"…"}`.

Example:

```powershell
$body = @{ pin="1234"; layout="SINGLE"; burnPolicy="CELL"; challengeMode="RANDOM" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/enroll -ContentType application/json -Body $body
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/challenge -ContentType application/json -Body "{}"
```

## Notes / limitations

- **Demo only / ephemeral state.** Everything lives in memory and is lost on restart. The card
  secret is kept server-side and is deliberately **not** exposed by the API; the `card_id`,
  printable grid are shown for teaching purposes (the PIN hash is redacted).
- **No secrets in the UI log.** Per the spec, the audit panel records results and state changes
  only; submitted words and PINs are never logged. The default PIN hashing iteration count is
  lowered to 10 000 for a snappy demo (configurable up to 1 000 000); production uses 210 000+.
- **Single user, single node.** `InMemoryBurnStore` / `InMemoryThrottleStore` are used; a real
  deployment needs a shared, atomic store.
- **No Maven is required** — and none is used. `run-demo.ps1` invokes `javac`/`java` directly.
