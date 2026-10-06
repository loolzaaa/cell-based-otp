# Changelog

All notable changes to the COTP project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Repository hygiene: root `LICENSE` (MIT), `.gitignore`, `CHANGELOG.md` and `README.md`.
- Apache Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
- GitHub Actions CI (`.github/workflows/ci.yml`) building and verifying on JDK 17 and 21 with Maven caching.
- JaCoCo coverage and Maven Enforcer (Java >= 17) plugins in `cotp-java/pom.xml`.
- Real JPMS module descriptor `cotp-java/src/main/java/module-info.java` (`module com.cotp { exports com.cotp; }`).
- Observability: `CotpEventSink` / `CotpEvent` / `CotpEventType` audit events in the core; `cotp-metrics`
  Micrometer module.
- Storage/integration: `CotpCredential` + `CotpCredentialCodec` (CRC) + `SchemeRegistry`; `CotpPolicyResolver`
  and the `Cotp.enroll/challenge/authenticate` facade; anti-oracle `ClientError`; typed exceptions
  (`CotpConfigurationException`, `CotpCryptoException`).
- Distributed state stores: `cotp-jdbc` (JDBC), `cotp-redis` (Lettuce) and the reusable
  `cotp-store-contract` conformance tests.
- `cotp-pdf` (PDFBox + ZXing) card template with a `render-sample.ps1` sample generator.
- `cotp-demo` (JDK HTTP server) and `docs/` (threat model, ops runbook, sequence diagrams);
  `INTEGRATION_GUIDE.md` and `examples/IntegrationExample.java`.

### Fixed

- `AesGcmSecretCipher.decrypt` now rejects truncated ciphertext deterministically as
  `CotpCryptoException` (some JDK 17 providers threw `ProviderException`/`ShortBufferException`
  before the tag check); also wraps unexpected provider `RuntimeException`s.
- Store contract tests use a far-future challenge expiry so TTL-native stores (Redis) do not expire
  the key mid-assertion.

## [1.0.0] - 2026-10-06

### Added

- Zero-dependency Java implementation of Cell-based One-Time Password (COTP): deterministic
  5x5 challenge-card authentication built on HKDF (RFC 5869) and HMAC-SHA-256.
- Reference COTP v1 derivation vectors and a standalone, Maven-free verification harness.
- JUnit 5 test suite (`cotp-java/src/test/java/com/cotp/CotpTest.java`).

[Unreleased]: https://example.invalid/cotp-java/compare/v1.0.0...HEAD
[1.0.0]: https://example.invalid/cotp-java/releases/tag/v1.0.0
