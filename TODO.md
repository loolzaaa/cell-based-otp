# TODO — проект COTP

> Рабочий список задач по развитию COTP (спецификация — `COTP_SPECIFICATION.md`,
> библиотека — `cotp-java/`). Приоритеты: **P1** — критично для пилота, **P2** — важно,
> **P3** — улучшения.

## Легенда исполнителя

| Метка | Значение |
|---|---|
| **[САМ]** | Выполнять текущему агенту: сквозные изменения ядра/crypto/API, нужен глубокий контекст. |
| **[СУБАГЕНТ]** | Можно делегировать субагенту: самодостаточно и хорошо специфицировано. |
| **[ИНФРА]** | Требует внешней инфраструктуры/доступов (Redis/БД, KMS, Maven Central, CI-секреты); код готовит субагент, проверка/деплой — человек/инфра. |
| **[СУБАГЕНТ + САМ ревью]** | Черновик делает субагент, финальную сверку с API — текущий агент. |

Формат пункта: цель → детали → критерии готовности (DoD) → **Исполнитель**.

---

## P1. Наблюдаемость и аудит

### [P1-A] Точки расширения в ядре: `CotpEventSink` + аудит-события — ✅ **сделано**
- [x] `CotpEventSink` (no-op по умолчанию) + `CotpEvent`/`CotpEventType`; инъекция в
      `CotpChallengeGenerator`, `CotpVerifier`, `Cotp`.
- [x] События: `CHALLENGE_ISSUED`, `VERIFY_SUCCEEDED`, `VERIFY_FAILED`, `CHALLENGE_REUSED_ATTEMPT`,
      `CARD_EXHAUSTED`, `CELL_BURNED`, `CELL_COOLDOWN_RECORDED`, `THROTTLE_LOCKOUT`.
- [x] Контекст аудита (`context`) на `CotpChallenge`, переносится в события верификации.
- [x] **[НОРМА]** события без секретов/слов/PIN/challengeId (тест «no secret leaked»).
- [x] Тесты: self-test + JUnit; ядро без внешних зависимостей; спецификация §16.8 обновлена.
- Разблокирует [P1-B] (метрики) и SIEM.

### [P1-B] Модуль метрик Prometheus `cotp-metrics` — ✅ **сделано (субагент)**

**Цель.** Видеть «здоровье» COTP в реальном времени (нагрузка, успешность, отказы, перебор,
лок-ауты, исчерпание, задержки), не загрязняя ядро зависимостями.

**Интеграция (выбрать):**
- **A (рекомендуется): Micrometer** (`micrometer-core`; в приложении `micrometer-registry-prometheus`,
  Actuator `/actuator/prometheus`). Обёртки `MetricsCotpCardGenerator`/`...ChallengeGenerator`/`...Verifier`
  или `CotpMetrics`-декоратор над `Cotp`.
- **B:** Prometheus Java client (`simpleclient`) — проще, но привязка к Prometheus.
- **C:** OpenTelemetry metrics — если уже есть OTel-стек.

Потребляет события из [P1-A] (`CotpEventSink`), не дублирует логику верификации.

**Метрики** (snake_case; **[НОРМА]** в labels НЕТ `card_id`/`employee_id`/`challenge_id`/`word`/`pin`):

| Метрика | Тип | Labels | Смысл |
|---|---|---|---|
| `cotp_challenge_issued_total` | counter | `mode`, `context` | Частота выдачи; всплеск = аномалия |
| `cotp_verification_total` | counter | `result`, `context` | Главный показатель исходов |
| `cotp_verification_duration_seconds` | histogram | `context`, `result` | Латентность (PBKDF2 + сетка), buckets 0.005…2 s |
| `cotp_grid_generation_duration_seconds` | histogram | `layout` | Стоимость HKDF+HMAC |
| `cotp_cell_burned_total` | counter | `layout` | Сожжённые ячейки (CELL) |
| `cotp_cards_exhausted_total` | counter | `layout` | Исчерпание карт → перевыпуск |
| `cotp_challenge_expired_total` | counter | `context` | Истёкшие челленджи |
| `cotp_challenge_reuse_total` | counter | — | Попытки replay (≈0) |
| `cotp_pin_verify_failures_total` | counter | `context` | Неверный PIN |
| `cotp_throttle_lockouts_total` | counter | `context` | Срабатывания lockout |
| `cotp_identity_mismatch_total` | counter | `context` | Несовпадение identity anchor |
| `cotp_enrollment_total` | counter | `layout` | Выпуск карт |
| `cotp_reissue_total` | counter | `reason` | Перевыпуск |
| `cotp_revocation_total` | counter | `reason` | Отзыв |
| `cotp_secret_decrypt_failures_total` | counter | — | Ошибки KMS/HSM (инцидент) |
| `cotp_active_cards` | gauge | `layout` | Активные карты |
| `cotp_collision_nonce` | histogram | `layout` | Распределение nonce (подбор словаря) |
| `cotp_challenge_ttl_seconds` | gauge | — | Настроенный TTL |
| `cotp_operations_total` | counter | `operation` | Общий трафик |

**Производные (PromQL):** success-ratio, доля `invalid_pin`, p95/p99 из histogram.

**Алерты (PromQL):**
```promql
sum(rate(cotp_pin_verify_failures_total[5m])) > 0.5          # перебор
increase(cotp_throttle_lockouts_total[15m]) > 5               # лок-ауты
increase(cotp_challenge_reuse_total[5m]) > 0                  # replay
increase(cotp_cards_exhausted_total[1h]) > 20                 # исчерпание
histogram_quantile(0.95, sum(rate(cotp_verification_duration_seconds_bucket[5m])) by (le)) > 1
increase(cotp_challenge_issued_total[10m]) > 200              # попытка «выучить» карты
```

**Интеграция:** зарегистрировать бин; `management.metrics.tags` (`application`/`environment`);
Grafana-дашборд JSON; конфиг `cotp.metrics.enabled/context-tag/histogram-buckets/include-layout-tag`.

**Безопасность:** не выводить секреты/PII; метки только категориальные; эндпоинт не в интернет.
Аномалии «по конкретной карте» — в аудит/SIEM (не в Prometheus, кардинальность).

**DoD:** модуль собирается, ядро без новых runtime-зависимостей, тесты на `SimpleMeterRegistry`,
тест «no forbidden tags», дашборд+алерты, замер overhead. Зависит от [P1-A].

> **Статус:** сделано (частично). `cotp-metrics/` (Micrometer): `MetricsEventSink`/`CotpMetrics`/
> декораторы, ~19 метрик, тесты (24). `mvnw` install ядра + `verify` метрик — **BUILD SUCCESS**.
> Core остаётся zero-dependency.
> **Осталось:** [ ] полный Grafana-дашборд JSON (сейчас только рекомендации панелей);
> [ ] замер overhead в бенчмарке.

---

## P2. Интеграция и хранение

### [P2-A] Кодек учётных данных карты + реестр схем — ✅ **сделано**
- [x] `CotpCredential` (immutable: `cardId`, `secret`, `schemeId`, `createdAt`, `expiresAt`;
      `fromCard`, `toCard(scheme/registry)`, secret скрыт в `toString`).
- [x] `CotpCredentialCodec`: формат `COTP1|cardId|schemeId|secretBase32|created|expires|CRC32` —
      версионированный, с контролем опечаток (tamper → исключение).
- [x] `SchemeRegistry` (version → `CotpScheme`): `defaults()`, `of(...)`, `register` (конфликт
      версий → ошибка), `resolve` (неизвестная версия → `CotpException`).
- [x] `CotpScheme.equals/hashCode/toString` для сравнения схем.
- [x] Тесты (self-test + JUnit): round-trip, обнаружение tamper, регенерация сетки, конфликт версий.
- [x] Спецификация §16.6/§19.2 обновлена.

### [P2-B] Фасадные хелперы и политика по контексту — ✅ **сделано**
- [x] `Cotp.enroll(...)` → `CotpEnrollment` (card + pinHash + credential); `Cotp.challenge(card,
      context, now)` и `Cotp.authenticate(...)` (policy по контексту челленджа); общие burn/throttle-store.
- [x] `CotpPolicyResolver` (`fixed`/`of`/`defaults`): `kiosk_login`→`COOLDOWN` 24 ч,
      `stepup`→`CHALLENGE` 60 с, `service_desk`→`CELL`; `null`/unknown → default.
- [x] Пример «Личного кабинета» (спека §16.9) + тесты (self-test + JUnit).

### [P2-C] Безопасный внешний ответ: `toClientError()` — ✅ **сделано**
- [x] `ClientError` (`NONE`/`AUTHENTICATION_FAILED`/`CHALLENGE_EXPIRED`/`CARD_EXPIRED`/
      `TOO_MANY_ATTEMPTS`/`BAD_REQUEST`) + `VerificationResult.toClientError()`.
- [x] Все фактор-специфичные отказы схлопываются в `AUTHENTICATION_FAILED` (anti-oracle).
- [x] Тесты (word и PIN → один и тот же `ClientError`); спецификация §11.7.

### [P2-D] Типизированные исключения — ✅ **сделано**
- [x] `CotpConfigurationException` (подтип `IllegalArgumentException`) — ошибки
      схемы/политики/словаря/credential; `CotpCryptoException` (подтип `CotpException`) — сбои
      HMAC/HKDF/AES-GCM/PBKDF2/деривации; `CardExhaustedException` — исчерпание.
- [x] Валидация `CotpScheme`/`CotpPolicy`/`WordDictionary`/`CotpCredential` переведена на
      `CotpConfigurationException`; крипто-операции — на `CotpCryptoException`.
- [x] Тесты (тип исключения) + спецификация §16.9.

### [P2-E] `PinHasher`: апгрейд и zeroization + SPI — **[СУБАГЕНТ]**
- `needsRehash(encoded)`, авто-апгрейд при `verify`, zeroization `char[]`, SPI для смены алгоритма
  (заготовка под Argon2id, см. P3-A).
- DoD: тесты миграции хэша, отсутствие регрессий формата.

### [P2-F] Rate limiting шире карты (per-IP/глобальный) — **[САМ]**
- Интерфейс `RateLimiter` рядом с `ThrottleStore` (NIST требует ограничение по нескольким осям).
- DoD: интерфейс + in-memory реализация + тесты.

### [P2-G] Распределённые хранилища состояния — ✅ **сделано (JDBC + Redis проверены)**
- [x] `cotp-jdbc`: `JdbcBurnStore`/`JdbcThrottleStore` поверх `DataSource`, DDL `JdbcStores`
      (`createSchema`); атомарность на PK/unique-констрейнтах и `UPDATE ... failures+1`.
      **Проверено на H2** (15 тестов, включая конкурентный инкремент, `evictExpired` и `JdbcUtil`).
- [x] `cotp-redis`: `RedisBurnStore`/`RedisThrottleStore` (Lettuce; `SET NX` + нативный TTL,
      `HINCRBY`); реализовано, но проверка требует живого Redis.
- [x] `cotp-store-contract`: переиспользуемые JUnit5 контракты `BurnStore`/`ThrottleStore`
      (используются JDBC и Redis).
- DoD: модули с внешней зависимостью собраны; conformance-контракт есть.
- [x] Redis-контракт прогнан на **живом Redis** (Docker `redis:7-alpine`, `COTP_REDIS_URI`) — **12/12**;
      JDBC-контракт на H2 — **15/15**. (Попутно исправлен общий контракт: фиксированный `expiresAt`
      заменён на далёкое будущее, иначе TTL-нативные хранилища истекали mid-assertion.)

### [P2-H] KMS-адаптер `SecretCipher` — **[СУБАГЕНТ] + [ИНФРА]**
- `SecretCipher` поверх AWS KMS / Azure Key Vault / Vault; поддержка key-id и ротации.
- Метрика `cotp_secret_decrypt_failures_total` (в P1-B).
- Сделано ранее: интерфейс `SecretCipher` + `AesGcmSecretCipher` + тесты (см. §12.3).

---

## P3. Крипто/фичи

### [P3-A] Argon2id для PIN — **[СУБАГЕНТ]**
- Опциональный провайдер PIN-хэша (BouncyCastle) через SPI, не ломая zero-dependency ядра.
- DoD: отдельный модуль, тесты совместимости/миграции.

### [P3-B] Алгоритмическая гибкость схемы — **[САМ]**
- Конфигурируемый HMAC (SHA-256/512) под новой версией `CotpScheme`; сейчас SHA-256 жёстко.
- DoD: новая версия, тест-векторы, спецификация.

### [P3-C] Multi-cell challenge (2 ячейки на операцию) — **[САМ]**
- Снижает реконструкцию сетки при наблюдении (§18.2) для высокоценных операций.
- DoD: режим + тесты + обновление модели угроз.

### [P3-D] Преcеты схем — **[СУБАГЕНТ]**
- `CotpScheme.avatierLike()` (TOP/BOTTOM 50), `compact()` (5×5), `bip39Standard()`.
- DoD: фабрики + тесты.

### [P3-E] Оффлайн-верификация service desk — **[САМ]**
- Модель доверия для локальной реплики секрета/HSM; сейчас пункт не раскрыт.
- DoD: дизайн-документ + прототип.

---

## P3. Качество, сборка, документация

### [Q-A] Гигиена репозитория и CI — ✅ **сделано (частично)**
- [x] Maven Wrapper (`mvnw`/`mvnw.cmd`), `.gitignore`, `LICENSE` (MIT), `CHANGELOG.md`, root `README.md`.
- [x] GitHub Actions `.github/workflows/ci.yml` (JDK 17/21, `mvn -B -f cotp-java/pom.xml verify`).
- [x] JPMS `module-info.java` (`module com.cotp { exports com.cotp; }`).
- [x] JaCoCo coverage + Maven Enforcer (`requireJavaVersion [17,)`); локально `mvnw verify` — **BUILD SUCCESS**, 43 теста.
- [ ] **Статический анализ** (ErrorProne/SpotBugs) в сборке/CI — **не добавлен**.
- [x] CI на GitHub прогнан: **JDK 21 — зелёный**; **JDK 17** выявил падение (`ProviderException`
      в AES-GCM на усечённом шифртексте) — **исправлено** (guard длины + `catch RuntimeException`),
      локально ядро перепроверено (178/42).

### [Q-B] Расширенное тестирование — **[СУБАГЕНТ]**
- Property-based/fuzz (jqwik): `Base32.decode`, `Cell.parse`, деривация, границы словаря.
- JMH-бенчмарк латентности верификации (доминирует PBKDF2) и генерации сетки.
- DoD: тесты в CI, отчёт бенчмарка.

### [Q-C] Документация проекта — ✅ **сделано**
- [x] `docs/THREAT_MODEL.md` — активы, акторы, STRIDE, границы доверия, ограничение look-up secret,
      остаточные риски.
- [x] `docs/OPERATIONS_RUNBOOK.md` — lifecycle, секреты/ключи, хранилища, инциденты, чек-листы.
- [x] `docs/SEQUENCE_DIAGRAMS.md` — mermaid-диаграммы: enrollment, вход на стойке (RFID+COTP),
      step-up, service desk, ревокация/перевыпуск.

### [Q-D] AGENTS.md для агентов-разработчиков — ✅ **сделано**
- [x] `AGENTS.md` в корне: структура репо, сборка без Maven (`run-verify.ps1`) и через `mvnw`,
      правила (zero-dependency ядра, не логировать секреты/PII, детерминированность, схема≠политика,
      `TIME_BASED`+`CELL`/`COOLDOWN` запрет, constant-time/атомарность), API-точки, порядок внесения
      изменений, метки исполнителя задач.

### [Q-E] Документация для программистов-интеграторов — ✅ **сделано**
- [x] `INTEGRATION_GUIDE.md` (11 разделов): подключение, жизненный цикл
      enroll → print → challenge → verify → re-enroll/revoke, хранение
      (`CotpCredential`/`Codec`/`SchemeRegistry`, `SecretCipher`), `CotpPolicyResolver`, anti-oracle
      `toClientError()`, наблюдаемость (`CotpEventSink` + `cotp-metrics`), REST-эскиз, анти-паттерны, FAQ.
- [x] `examples/IntegrationExample.java` — **компилируемый и запускаемый** сквозной пример
      (проверено: `javac`+`java` exit 0, вывод `FLOW: OK`).
- [x] Сверено с фактическим API `cotp-java` и `cotp-metrics`.

### [Q-F] Публикация артефактов — **[ИНФРА]**
- JavaDoc + артефакты во внутренний Maven-репозиторий (подпись, версионирование).
- DoD: релиз доступен потребителям.

### [Q-G] Формальный security-review — **[СУБАГЕНТ] + внешний**
- Независимый аудит схемы и threat-model (не только кода).
- DoD: отчёт, устранение замечаний.

---

## Продукт/UX

### [U-A] Графическое (веб) демо работы библиотеки — ✅ **сделано (частично)**
- [x] `cotp-demo/` на JDK `HttpServer` (ноль зависимостей), использует **реальную** библиотеку:
      enrollment (сетка + печать), challenge (ячейка + TTL), verify (слово+PIN), политики
      `CELL/COOLDOWN/CHALLENGE`, `TIME_BASED`, throttling/lockout, панель аудит-лога.
- [x] `run-demo.ps1` (строгая сборка + проверка полноты classpath), `README.md`.
- [x] Исправлены баги: `NoClassDefFoundError` (incomplete classpath) и запрещённая комбинация
      `TIME_BASED`+`CELL/COOLDOWN` (UI сбрасывает режим + серверный 4xx).
- [ ] Скриншот/видео-демонстрация — **не приложены**. DoD в остальном выполнен.

### [U-B] JS/TS-клиент для фронта — **решение: тонкий, без крипто — [СУБАГЕНТ]**
- **Анализ:** крипто-JS **не нужен** — вся проверка серверная, пользователь лишь читает слово с карты и
  передаёт слово+PIN по TLS. Отдельная клиентская крипто-часть не требуется.
- Полезен **тонкий REST-клиент + UI-виджет** (отображение ячейки, ввод, ошибки, таймер TTL) для
  «Личного кабинета» — опционально.
- DoD: `cotp-web-sdk` (fetch + виджет) или пример интеграции в `examples/`.

### [U-C] QR/Apple Wallet + PDF-шаблон карты — ✅ **сделано (PDF+QR; Apple Wallet — нет)**
- [x] `cotp-pdf`: `CotpCardPdf` (PDFBox) — печатная карта (сетка SINGLE/TOP-BOTTOM, заголовки
      A–E/1–5, `cardId`, версия/даты, инструкция, **QR с `cardId`**, без PII/секрета); опции `CotpPdfOptions`.
      **Проверено** (5 тестов, `%PDF-`, 1 страница, текст-извлечение содержит слово/`cardId`).
- [x] `cotp-pdf/render-sample.ps1` + `SampleCardPdf` — генерация `cotp-pdf/build/sample-card.pdf`
      (SINGLE/WORD_PAIRS; проверено: `%PDF-`, 1 страница, openable).
- [ ] Apple Wallet-версия пасса — не реализована (опционально).

### [U-D] Локализация словаря (RTL/CJK) — **[СУБАГЕНТ]**
- Готовые словари и проверка читаемости; правила нормализации.
- DoD: словари + тесты.

---

## Уже сделано (в рамках предыдущих итераций)

- [x] Ядро: схема/политика разделены (`CotpScheme`/`CotpPolicy`), удалён `CotpConfig`.
- [x] HKDF (RFC 5869), детерминированная сетка, тест-векторы, доказательство детерминированности.
- [x] `BurnPolicy`: `CELL`/`COOLDOWN`/`CHALLENGE`/`NONE`; `cellCooldown`; `TIME_BASED`+`CELL/COOLDOWN` запрет.
- [x] TTL-эвикция challenge-записей; эвикция idle-throttle; `reset(cardId)`.
- [x] `SecretCipher` + `AesGcmSecretCipher` (шифрование at-rest).
- [x] Атомарность check-and-set, throttling/lockout, constant-time, identity без throttle.
- [x] Строгий Base32, строгий `Cell.parse`, Unicode-валидация словаря/версии.
- [x] Аудит-события в ядре: `CotpEventSink`/`CotpEvent`/`CotpEventType`, контекст на челлендже (P1-A).
- [x] Метрики (P1-B): модуль `cotp-metrics` (Micrometer), `MetricsEventSink`/`CotpMetrics`, ~19 метрик.
- [x] Учётные данные (P2-A): `CotpCredential` + `CotpCredentialCodec` (CRC) + `SchemeRegistry`.
- [x] Фасад/resolver (P2-B): `Cotp.enroll/challenge/authenticate`, `CotpPolicyResolver`,
      `CotpEnrollment`.
- [x] Anti-oracle внешний ответ (P2-C): `ClientError` + `VerificationResult.toClientError()`.
- [x] Типизированные исключения (P2-D): `CotpConfigurationException`, `CotpCryptoException`.
- [x] Гигиена/CI (Q-A): `.gitignore`, `LICENSE`, `CHANGELOG`, GitHub Actions, Maven Wrapper,
      `module-info.java`, JaCoCo + Enforcer.
- [x] Веб-демо (U-A): `cotp-demo/` (JDK HttpServer, без зависимостей); исправлены баги сборки и
      запрещённой комбинации `TIME_BASED`+`CELL/COOLDOWN`.
- [x] Документация (Q-C): `docs/THREAT_MODEL.md`, `docs/OPERATIONS_RUNBOOK.md`,
      `docs/SEQUENCE_DIAGRAMS.md`.
- [x] AGENTS.md (Q-D): руководство для агентов-разработчиков.
- [x] Интеграционный гайд (Q-E): `INTEGRATION_GUIDE.md` + `examples/IntegrationExample.java`.
- [x] Распределённые хранилища (P2-G): `cotp-jdbc` (H2-проверено) + `cotp-redis` (Lettuce,
      live-тесты [ИНФРА]) + `cotp-store-contract`.
- [x] PDF+QR карты (U-C): `cotp-pdf` (PDFBox+ZXing).
- [x] Аудит (round 4) — исправлено: idle-вытеснение throttle (C1, lockout bypass), кодирование
      Redis-ключей (C2, коллизия `:`), уточнение контракта COOLDOWN (C3), TIME_BASED TTL при
      `timeWindowBack=0` (C4), утечка PIN-хэша в демо (C6), обёртка Redis-исключений (C7),
      гейтинг эвикции JDBC + `purge()` (C8); добавлены регресс-тесты. C5 пропущен (недостижимо).
- [x] Аудит (round 5) — исправлено: Redis-индекс челленджей теперь ZSET с trim (N1), TTL TIME_BASED =
      остаток окна (N2), `getNextException` в JDBC (N3), синхронизация доков (N4), sanitize/обёртка PDF
      (N5), null-safe `SchemeRegistry` (N6), границы окон схемы (N7); delta-аудит доработал: TTL индекса
      Redis не уменьшается (N1), TTL TIME_BASED с долями секунды (N2), обёртка `create()`/`close()`
      (C7); добавлены тесты (ядро 43, jdbc 15, redis 12).
- [x] Тесты: ядро self-test 178/178, JUnit 43/43; модули: metrics 24, jdbc 15, pdf 5, redis 12
      (на живом Redis; без `COTP_REDIS_URI` — skipped).
