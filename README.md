# COTP — Cell-based One-Time Password

Zero-dependency Java library for **COTP**: a deterministic, printable 5×5 challenge-card
authentication factor, built as an analog of the *Avatier Identity Challenge Card*.

- **Java library:** [`cotp-java/`](cotp-java/) — see [`cotp-java/README.md`](cotp-java/README.md)
  for the full overview, build instructions and API quick start.
- **Specification:** [`COTP_SPECIFICATION.md`](COTP_SPECIFICATION.md)
- **Integration guide:** [`INTEGRATION_GUIDE.md`](INTEGRATION_GUIDE.md) +
  runnable example [`examples/IntegrationExample.java`](examples/IntegrationExample.java).
- **Metrics module:** [`cotp-metrics/`](cotp-metrics/) (optional, Micrometer).
- **PDF/QR card template:** [`cotp-pdf/`](cotp-pdf/) (optional, PDFBox + ZXing).
- **Distributed state stores:** [`cotp-jdbc/`](cotp-jdbc/) (DataSource) and
  [`cotp-redis/`](cotp-redis/) (Lettuce); shared contract in [`cotp-store-contract/`](cotp-store-contract/).
- **Web demo:** [`cotp-demo/`](cotp-demo/) (JDK HTTP server, no dependencies).
- **Security & ops:** [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md),
  [`docs/OPERATIONS_RUNBOOK.md`](docs/OPERATIONS_RUNBOOK.md),
  [`docs/SEQUENCE_DIAGRAMS.md`](docs/SEQUENCE_DIAGRAMS.md).
- **Roadmap / backlog:** [`TODO.md`](TODO.md)

## Quick start

Build and verify the library with the bundled Maven Wrapper (requires JDK 17+):

```bash
./cotp-java/mvnw -f cotp-java/pom.xml verify
```

On Windows:

```powershell
.\cotp-java\mvnw.cmd -f cotp-java\pom.xml verify
```

Or, without Maven at all, run the dependency-free self-test:

```powershell
powershell -ExecutionPolicy Bypass -File .\cotp-java\run-verify.ps1
```

## Requirements

* JDK 17 or newer.
* No runtime dependencies (the library uses only the JDK).

## License

MIT — see [`LICENSE`](LICENSE). Copyright (c) 2026 COTP project.
