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

## [1.0.0] - 2026-10-06

### Added

- Zero-dependency Java implementation of Cell-based One-Time Password (COTP): deterministic
  5x5 challenge-card authentication built on HKDF (RFC 5869) and HMAC-SHA-256.
- Reference COTP v1 derivation vectors and a standalone, Maven-free verification harness.
- JUnit 5 test suite (`cotp-java/src/test/java/com/cotp/CotpTest.java`).

[Unreleased]: https://example.invalid/cotp-java/compare/v1.0.0...HEAD
[1.0.0]: https://example.invalid/cotp-java/releases/tag/v1.0.0
