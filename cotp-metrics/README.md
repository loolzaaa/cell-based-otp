# COTP Metrics — Prometheus / Micrometer adapter

Observability adapter for the [`cotp-java`](../cotp-java) core. It subscribes to the core's audit
events (`CotpEventSink`, task `[P1-A]`) and translates them into Micrometer meters (Prometheus-ready
via `micrometer-registry-prometheus`).

**The core stays dependency-free.** `cotp-metrics` is a *separate* module: the zero-runtime-dependency
`cotp-java` artifact is not modified and gains no transitive dependencies.

- Coordinates: `com.cotp:cotp-metrics:1.0.0`
- Runtime deps: `com.cotp:cotp-java:1.0.0`, `io.micrometer:micrometer-core`
- JDK baseline: 17
- Tests: JUnit 5 + `SimpleMeterRegistry` (from `micrometer-core`)

## 1. Quick start

```java
import com.cotp.Cotp;
import com.cotp.CotpScheme;
import com.cotp.CotpPolicy;
import com.cotp.metrics.CotpMetrics;
import io.micrometer.core.instrument.MeterRegistry;

MeterRegistry registry = /* your registry, e.g. PrometheusMeterRegistry */;
CotpMetrics metrics = CotpMetrics.create(registry, "5x5");   // layout label for layout-tagged meters

// Wire the sink into the core — no core dependency on Micrometer:
Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());

// Optional: latency histograms + operation tally via delegating decorators
var verifier = metrics.verifier(cotp.verifier());
var generator = metrics.cardGenerator(cotp.cardGenerator());
var challenges = metrics.challengeGenerator(cotp.challengeGenerator());
```

Every challenge issue / verification outcome / burn / lockout is then counted automatically. The
decorators only add what a single event cannot carry (call latency) — they do not change policy or
state.

## 2. Spring Boot Actuator + Prometheus

Add the Prometheus registry (Spring Boot manages the version via its Micrometer BOM; pin it if you
prefer):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>com.cotp</groupId>
  <artifactId>cotp-metrics</artifactId>
  <version>1.0.0</version>
</dependency>
```

`application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: my-app      # common tags applied to every meter
      environment: prod
  endpoint:
    prometheus:
      enabled: true
# cotp.metrics.* is application configuration consumed by your @Configuration; the library
# itself is configuration-free. Suggested keys:
#   cotp.metrics.enabled: true
#   cotp.metrics.layout: 5x5
#   cotp.metrics.context-tag: true
#   cotp.metrics.include-layout-tag: true
```

Spring `@Configuration`:

```java
@Configuration
class CotpMetricsConfiguration {
  @Bean
  CotpMetrics cotpMetrics(MeterRegistry registry) {
    return CotpMetrics.create(registry, "5x5");
  }

  @Bean
  Cotp cotp(CotpMetrics metrics) {
    return new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());
  }
}
```

Scrape endpoint: `GET /actuator/prometheus`. **Do not expose it to the internet** — keep it on the
internal management/observability network.

## 3. Meter reference

All names use the `cotp` prefix and `snake_case`; labels are categorical only.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `cotp_challenge_issued_total` | counter | `mode`, `context` | Challenge issue frequency (spike = anomaly) |
| `cotp_verification_total` | counter | `result`, `context` | Primary outcome indicator |
| `cotp_verification_duration_seconds` | histogram | `context`, `result` | Verification latency (PBKDF2 + grid); buckets 5 ms … 2 s |
| `cotp_grid_generation_duration_seconds` | histogram | `layout` | HKDF + HMAC grid materialization cost |
| `cotp_cell_burned_total` | counter | `layout` | Burned cells (`BurnPolicy.CELL`) |
| `cotp_cards_exhausted_total` | counter | `layout` | Exhausted cards → re-enroll |
| `cotp_challenge_expired_total` | counter | `context` | Expired challenges |
| `cotp_challenge_reuse_total` | counter | — | Replay attempts (should be ≈0) |
| `cotp_pin_verify_failures_total` | counter | `context` | Wrong PIN |
| `cotp_throttle_lockouts_total` | counter | `context` | Lockout activations |
| `cotp_identity_mismatch_total` | counter | `context` | Identity-anchor mismatch |
| `cotp_enrollment_total` | counter | `layout` | Cards enrolled (set explicitly) |
| `cotp_reissue_total` | counter | `reason` | Reissues (set explicitly) |
| `cotp_revocation_total` | counter | `reason` | Revocations (set explicitly) |
| `cotp_secret_decrypt_failures_total` | counter | — | KMS/HSM decrypt failures (incident) |
| `cotp_active_cards` | gauge | `layout` | Active cards (set explicitly) |
| `cotp_collision_nonce` | histogram | `layout` | Duplicate-word nonce distribution |
| `cotp_challenge_ttl_seconds` | gauge | — | Configured challenge TTL |
| `cotp_operations_total` | counter | `operation` | Coarse traffic tally (event/operation) |

**Event-driven** (automatic through `eventSink()`): all counters above except enrollment / reissue /
revocation / secret-decrypt / operations, which the application drives.

**Application-driven** API on `CotpMetrics`:

```java
metrics.recordEnrollment("5x5");
metrics.recordReissue("lost_card");
metrics.recordRevocation("compromised");
metrics.recordSecretDecryptFailure();
metrics.recordOperation("print_card");
metrics.setActiveCards("5x5", fleet.countActiveCards());
metrics.setChallengeTtlSeconds(policy.challengeTtl().toSeconds());
metrics.recordCollisionNonce("5x5", nonce);
```

## 4. Security & cardinality

- **[НОРМА]** Tag keys are limited to `result`, `context`, `mode`, `layout`, `reason`, `operation`.
  Identifiers and secrets (`card_id`, `employee_id`, `challenge_id`, `word`, `pin`, `secret`) are
  **never** used as tags. The event's `cardId`/`cellId` are read but deliberately discarded.
- **High cardinality:** keep `context` a small, application-owned vocabulary (`kiosk_login`,
  `stepup`, `service_desk`, …) and `reason`/`operation` bounded. Per-card anomalies belong in the
  audit/SIEM stream, not in Prometheus.
- Null/blank tag values are normalized (`context` → `none`, others → `unknown`) so no label is empty.
- Metrics never contain secrets or PII; a dedicated test (`NoForbiddenTagsTest`) runs a full flow and
  asserts it.

## 5. Suggested PromQL

Derived indicators:

```promql
# success ratio
sum(rate(cotp_verification_total{result="OK"}[5m]))
  / sum(rate(cotp_verification_total[5m]))

# invalid PIN share
sum(rate(cotp_pin_verify_failures_total[5m]))
  / sum(rate(cotp_verification_total[5m]))

# p95 / p99 verification latency
histogram_quantile(0.95, sum(rate(cotp_verification_duration_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(cotp_verification_duration_seconds_bucket[5m])) by (le))
```

Alert rules (`TODO.md` `[P1-B]`):

```promql
sum(rate(cotp_pin_verify_failures_total[5m])) > 0.5            # brute force
increase(cotp_throttle_lockouts_total[15m]) > 5                # lockouts
increase(cotp_challenge_reuse_total[5m]) > 0                   # replay
increase(cotp_cards_exhausted_total[1h]) > 20                  # exhaustion
histogram_quantile(0.95, sum(rate(cotp_verification_duration_seconds_bucket[5m])) by (le)) > 1
increase(cotp_challenge_issued_total[10m]) > 200               # card-learning attempt
```

## 6. Grafana panel suggestions (text)

A full dashboard JSON is out of scope; suggested panels:

1. **Success ratio (stat/gauge)** — the success-ratio query above; thresholds 0.95 / 0.99.
2. **Outcomes over time (stacked timeseries)** —
   `sum(rate(cotp_verification_total[5m])) by (result)`.
3. **Challenge issue rate (timeseries)** —
   `sum(rate(cotp_challenge_issued_total[5m])) by (context)`.
4. **Verification latency (timeseries)** — p50/p95/p99 from
   `cotp_verification_duration_seconds_bucket`.
5. **Lockouts & brute force (timeseries + alert)** —
   `increase(cotp_throttle_lockouts_total[15m])`,
   `sum(rate(cotp_pin_verify_failures_total[5m]))`.
6. **Playback / reuse (stat)** — `increase(cotp_challenge_reuse_total[1h])` (red when > 0).
7. **Card lifecycle (timeseries)** — `increase(cotp_cell_burned_total[1h])`,
   `increase(cotp_cards_exhausted_total[1h])`, `cotp_active_cards`.
8. **Secret/KMS health (stat)** — `increase(cotp_secret_decrypt_failures_total[1h])` (red when > 0).

## 7. Build & test

```powershell
# install the core into the local repo first
cotp-java\mvnw.cmd -B -f C:\Project\cell-based-otp\cotp-java\pom.xml install
# build + verify this module
cotp-java\mvnw.cmd -B -f C:\Project\cell-based-otp\cotp-metrics\pom.xml verify
```

The test suite uses `SimpleMeterRegistry` and covers every event type, the latency histograms, the
gauges, a full `Cotp` lifecycle, and the "no forbidden tags" guarantee.

## License

MIT.

## Note on `micrometer-registry-simple`

There is no `io.micrometer:micrometer-registry-simple` artifact on Maven Central;
`SimpleMeterRegistry` (`io.micrometer.core.instrument.simple.SimpleMeterRegistry`) is part of
`micrometer-core`, which is already a compile dependency of this module.
