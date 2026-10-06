# AGENTS.md — разработка библиотеки COTP

Руководство для агентов (и людей), которые развивают этот репозиторий. Кратко, по делу, с
правилами, которые нельзя нарушать.

## Что это

**COTP** (Cell-based One-Time Password) — печатная карта-сетка 5×5 слов как второй фактор
аутентификации (аналог Avatier Identity Challenge Card). Сервер хранит **только секрет карты**;
сетка слов детерминированно регенерируется (HKDF + HMAC-SHA-256). Полная спецификация —
`COTP_SPECIFICATION.md` (авторитетный источник).

## Структура репозитория

| Путь | Что |
|---|---|
| `cotp-java/src/main/java/ru/loolzaaa/` | Ядро библиотеки (ноль runtime-зависимостей). |
| `cotp-java/src/main/resources/ru/loolzaaa/cotp-english.txt` | Словарь BIP-39 (2048 слов). |
| `cotp-java/src/test/java/ru/loolzaaa/` | JUnit 5 тесты. |
| `cotp-java/tools/` | `CotpSelfTest` (автономная проверка), `CotpVectorPrinter` (векторы). |
| `cotp-java/run-verify.ps1` | Сборка + self-test без Maven. |
| `cotp-metrics/` | Опциональный модуль метрик (Micrometer), зависит от ядра. |
| `cotp-demo/` | Веб-демо (JDK HttpServer), без внешних зависимостей. |
| `docs/` | Threat-model и ops-runbook. |
| `COTP_SPECIFICATION.md`, `TODO.md`, `README.md` | Спека, план, обзор. |

## Сборка и проверка

```powershell
# Быстро, без Maven (JDK 17+ на PATH):
powershell -ExecutionPolicy Bypass -File cotp-java\run-verify.ps1   # ожидаем PASS=178 FAIL=0

# Полная сборка через Maven Wrapper:
cotp-java\mvnw.cmd -B -f cotp-java\pom.xml verify                    # BUILD SUCCESS, 43 теста
```

JUnit без Maven: запустите `mvnw` (полная сборка с тестами) или self-test через
`run-verify.ps1`. **Перед сдачей задачи обязательно** прогоняйте `run-verify.ps1` и, если меняли
API, JUnit.

## Незыблемые правила

1. **Ядро `cotp-java` — zero-dependency.** Не добавлять runtime-зависимости (только JDK). Внешние
   интеграции (Redis, JDBC, KMS, Micrometer, Argon2) — **отдельными модулями** (`cotp-metrics`,
   будущие `cotp-redis`/`cotp-jdbc`/`cotp-kms`).
2. **Секреты/PII никогда не логируются и не попадают в метрики/события.** Запрещены в логах,
   `CotpEvent` и labels: `card_secret`, слова, PIN, `challengeId`; `card_id`/`employee_id` — только
   в аудит при необходимости и осознанно.
3. **Детерминированность.** `CotpDerivation` и генерация сетки — чистые функции. Порядок слотов
   канонический; словарь — степень двойки. Любое изменение label/раскладки/словаря = **новая
   версия схемы** (`CotpScheme.version()`), иначе инвалидируются все выданные карты.
4. **Схема ≠ политика.** `CotpScheme` (version/словарь/раскладка/шаг времени) — идентичность карты;
   `CotpPolicy` (режим/burn/cooldown/TTL/throttle/PIN) — операционная политика. Деривация всегда
   идёт по `card.scheme()`.
5. **`TIME_BASED` несовместим с `BurnPolicy.CELL`/`COOLDOWN`.** При `TIME_BASED` без явной политики
   по умолчанию `CHALLENGE`; явная запрещённая пара отвергается при сборке `CotpPolicy`.
6. **Constant-time** для сравнения слов/PIN; **атомарный** check-and-set в хранилищах состояния.
7. **Не коммитить** без явной просьбы; не менять git-конфиг; не пушить.

## Конвенции кода

- Java 17 (`maven.compiler.release=17`), пакет `ru.loolzaaa`, JPMS-модуль `ru.loolzaaa` (`module-info`).
- Минимум комментариев; Javadoc на публичном API; идентификаторы на английском.
- Неизменяемые типы по возможности; `record` для value-типов (`Cell`, `CotpChallenge`, `CotpEvent`).
- Новые публичные классы автоматически экспортируются модулем `ru.loolzaaa`.

## Как вносить изменения

1. Прочитать `COTP_SPECIFICATION.md` (раздел по теме) и `TODO.md`.
2. Реализовать, покрыв тестами: self-test (`tools/CotpSelfTest.java`) и/или JUnit.
3. Для изменений в деривации: обновить `CotpVectorPrinter`/эталонные векторы и раздел §14 спеки.
4. Прогнать `run-verify.ps1` (+ `mvnw verify` при наличии) до зелёного.
5. Обновить спеку/`TODO.md` (отмечать сделанное), если менялось поведение/API.

## Полезные API-точки

- Фасад: `Cotp` (`enroll`-паттерн: `generateCard`, `newPinHash`; `challengeGenerator()`,
  `verifier()`).
- Схема/политика: `CotpScheme.builder()`, `CotpPolicy.builder()`, `SchemeRegistry`.
- Хранение: `CotpCredential` + `CotpCredentialCodec` (версионированный формат с CRC),
  `SecretCipher`/`AesGcmSecretCipher`.
- Состояние: `BurnStore`/`InMemoryBurnStore`, `ThrottleStore`/`InMemoryThrottleStore`.
- Наблюдаемость: `CotpEventSink`/`CotpEvent`/`CotpEventType` (ядро) → `cotp-metrics`.

## Открытые задачи

Смотрите `TODO.md` (P1/P2/P3 + продуктовые/инфраструктурные). Там у каждого пункта указана метка
исполнителя: `[САМ]` (нужен глубокий контекст ядра), `[СУБАГЕНТ]`, `[ИНФРА]`.
