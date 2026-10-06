# COTP — руководство по интеграции (Java)

> **Назначение.** Практическое руководство для программистов, встраивающих библиотеку `cotp-java`
> в приложение («Личный кабинет сотрудника», киоск, step-up сервисов). Описывает жизненный цикл
> карты, хранение секрета и состояния, политики по сценариям, внешние ответы, наблюдаемость,
> REST-эскиз, анти-паттерны и FAQ.
> **Статус.** Сверено с исходным кодом `cotp-java/src/main/java/ru/loolzaaa/` (версия `1.0.0`) и
> `COTP_SPECIFICATION.md` v1.0. Все сигнатуры и примеры ниже — рабочие.
> **Язык:** русский; идентификаторы, классы, RFC и имена метрик — в латинице.
> **Дата:** 2026-10-06.

Сопутствующий компилируемый пример: [`examples/IntegrationExample.java`](examples/IntegrationExample.java)
(проходит полный цикл, шифрует credential через `AesGcmSecretCipher` и печатает результат).

---

## 1. Что такое COTP

**COTP (Cell-based One-Time Password)** — второй фактор аутентификации на **печатной карте 5×5
слов**. Пользователь читает слово из указанной сервером ячейки (например `C2`) и предъявляет его
вместе с приватным PIN. Сервер хранит **только секрет карты** (`card_secret`), а всю сетку слов
детерминированно восстанавливает из секрета через `HKDF` (RFC 5869) и `HMAC-SHA-256`.

Аналогия с TOTP (RFC 6238): то же `code = F(secret, input)`, но `input` — не время, а **ячейка**, а
код — **слово** из словаря. Дизайн вдохновлён Avatier Identity Challenge Card; COTP — **не**
стандарт, стандартны только примитивы (HMAC/HKDF/PBKDF2/Base32/CSPRNG). COTP — **look-up secret**
(NIST SP 800-63B §5.1.2), не phishing-resistant.

Полная спецификация: [`COTP_SPECIFICATION.md`](COTP_SPECIFICATION.md). Ключевые разделы:
§11 (верификация), §11.7 (anti-oracle), §12.3 (`SecretCipher`), §16 (интеграция),
§16.8 (события), §16.9 (фасад/resolver), §19 (библиотека).

---

## 2. Требования и подключение

| Параметр | Значение |
|---|---|
| JDK baseline | **17+** (собрано/проверено на Temurin 21) |
| Runtime-зависимости ядра | **нет** (только JDK: `javax.crypto.Mac`, `java.security`) |
| Координаты | `ru.loolzaaa:cotp-java:1.0.0` (MIT) |
| JPMS | модуль `ru.loolzaaa`, экспортирует `ru.loolzaaa` |
| Опционально | `ru.loolzaaa:cotp-metrics:1.0.0` (Micrometer; зависит от ядра) |

Maven:

```xml
<dependency>
  <groupId>ru.loolzaaa</groupId>
  <artifactId>cotp-java</artifactId>
  <version>1.0.0</version>
</dependency>

<!-- опционально: метрики Prometheus/Micrometer -->
<dependency>
  <groupId>ru.loolzaaa</groupId>
  <artifactId>cotp-metrics</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation("ru.loolzaaa:cotp-java:1.0.0")
// опционально: implementation("ru.loolzaaa:cotp-metrics:1.0.0")
```

Ядро **не тянет** внешних зависимостей; всё, что требует SDK (Redis, JDBC, KMS/HSM, Micrometer,
Argon2), выносится в **отдельные модули**. Пример компиляции без Maven/Gradle и запуска — в
шапке [`examples/IntegrationExample.java`](examples/IntegrationExample.java).

---

## 3. Жизненный цикл: enroll → print → challenge → verify → re-enroll/revoke

Один серверный объект `Cotp` инкапсулирует схему, политику, генератор карт/челленджей, верификатор
и общие in-memory хранилища состояния. **Хранилища у фасада собственные** (`cotp.burnStore()`,
`cotp.throttleStore()`); для общих/распределённых используйте `CotpVerifier`/`CotpChallengeGenerator`
напрямую (см. §4).

### 3.1. Enrollment (выпуск)

```java
Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicyResolver.defaults());

char[] pin = "1234".toCharArray();                       // задаётся пользователем
CotpEnrollment enr = cotp.enroll("COTP-DEMO-0001", pin); // или cotp.enroll(pin) -> новый id

CotpCard card = enr.card();          // card.grid() — что печатать; card.secret() — секрет (шифровать!)
String pinHash = enr.pinHash();      // persist отдельно от секрета
String encoded = enr.credentialEncoded(); // COTP1|... содержит секрет -> хранить зашифрованным
```

`newCardId()` (80-бит Base32, CSPRNG) — если нужен публичный id до вызова `enroll`.
`enroll(String cardId, char[] pin, String context)` использует политику, разрешённую для
`context` (например, число итераций PBKDF2).

### 3.2. Print

```java
System.out.print(CotpCardPrinter.render(card));   // ASCII-таблица (SINGLE и TOP/BOTTOM)
```

На карте печатается **только** `card_id`, версия, даты и инструкция — **без PII и без PIN**
(§12.2, §15). PDF/QR-шаблон — вне библиотеки.

### 3.3. Challenge (выдача ячейки)

```java
Instant now = Instant.now();
CotpChallenge ch = cotp.challenge(card, "kiosk_login", now);  // политика по контексту
// Киоск показывает ch.cellId(), например "C2"; ch.expiresAt() — TTL.
```

Эквивалент через генератор (та же политика фасада):

```java
CotpChallenge a = cotp.challengeGenerator().random(card, now, cotp.burnStore());
CotpChallenge b = cotp.challengeGenerator().timeBased(card, now);   // TIME_BASED
```

При исчерпании доступных ячеек генератор бросает `CardExhaustedException` (см. FAQ).

### 3.4. Verify (проверка)

```java
String word = card.wordAt(ch.cell());   // на сервере; в реальном UI слово приходит от пользователя
char[] userPin = "1234".toCharArray();

VerificationResult r = cotp.authenticate(card, pinHash, ch, word, userPin, now);
ClientError client = r.toClientError();               // наружу — только это (anti-oracle, §6)
if (client.isSuccess()) { /* выдать сессию / step-up токен */ }
```

Ручной вариант с identity anchor (fail-closed на стороне приложения):

```java
VerificationResult r2 = cotp.verifier().verify(
        card, pinHash, ch, word, userPin,
        presentedEmployeeId, registeredEmployeeId, now);
```

### 3.5. Re-enroll / revoke

У библиотеки **нет** API статуса/отзыва: `ACTIVE|REVOKED|EXPIRED` и модель данных ведёт
приложение (§16.6, runbook §5). Процедура:

```java
burnStore.reset(oldCardId);        // очищает сожжённые ячейки И использованные челленджи
throttleStore.reset(oldCardId);

CotpEnrollment next = cotp.enroll(cotp.newCardId(), pin);  // НОВЫЙ card_id + НОВЫЙ secret
```

Триггеры: `CARD_EXHAUSTED` / `CardExhaustedException`, истечение `card.expiresAt()`, утеря/копия,
плановая ротация (180–365 дней). Никогда не переиспользуйте `card_secret`. Отдельного API отзыва
нет — просто прекратите выдавать челленджи по этому `cardId`.

---

## 4. Хранение

### 4.1. Credential и кодек

`CotpCredential` — единственный секрет, который надо персистить: `cardId` + `secret` + `schemeId`
(+ `createdAt`/`expiresAt`). Сетка **не хранится** — регенерируется.

```java
CotpCredential cred = CotpCredential.fromCard(card);      // snapshot
String encoded = CotpCredentialCodec.encode(cred);        // COTP1|cardId|schemeId|secretB32|created|expires|CRC32
CotpCredential back = CotpCredentialCodec.decode(encoded); // bad CRC -> CotpException
CotpCard restored = back.toCard(CotpScheme.defaults());   // версия схемы обязана совпасть
```

### 4.2. Реестр схем (`SchemeRegistry`)

Ключ — `CotpScheme.version()`. Нужен для мультиверсионных развёртываний: credential хранит только
версию, полную схему резолвит реестр.

```java
SchemeRegistry registry = SchemeRegistry.defaults();       // регистрирует CotpScheme.defaults()
registry.register(CotpScheme.builder().version("COTP/v2").build());
CotpCard card2 = cred.toCard(registry);                    // resolve по schemeId
CotpScheme s = registry.resolve("COTP/v1");                // неизвестная версия -> CotpException
```

### 4.3. Шифрование секрета at-rest (`SecretCipher`)

```java
SecretCipher cipher = AesGcmSecretCipher.generate();       // 256-бит ключ; в prod — из KMS/HSM
String sealed = cipher.encryptToBase64(encoded.getBytes(StandardCharsets.UTF_8));
// ... сохранить sealed ...
CotpCredential loaded = CotpCredentialCodec.decode(
        new String(cipher.decryptFromBase64(sealed), StandardCharsets.UTF_8));
```

| Свойство `AesGcmSecretCipher` | Значение |
|---|---|
| Алгоритм | AES-GCM (`AES/GCM/NoPadding`) |
| Ключ | 16/24/32 байта (рекомендуется 32 = AES-256) |
| IV | свежий 96-бит (12 байт) на **каждое** шифрование |
| Тег | 128 бит; формат `IV ‖ ciphertext+tag` |
| `generate()` | эфемерный ключ (тесты/демо); `keyBytes()` — копия для обёртки/ротации в KMS |

KMS/HSM-адаптер реализует тот же `SecretCipher` (`encrypt`/`decrypt` + Base64-хелперы) в отдельном
модуле; ядро остаётся zero-dependency. Шифруйте **либо** весь `encoded` credential, **либо** только
`secret().copyBytes()`.

### 4.4. PIN-хэш

Хранится строка формата `pbkdf2-sha256$<iterations>$<salt B64>$<hash B64>` (соль 16 B, выход 256
бит, по умолчанию 210 000 итераций). PIN не логируется; на карте не печатается.

```java
String pinHash = cotp.newPinHash("1234".toCharArray());
boolean ok = cotp.verifyPin("1234".toCharArray(), pinHash);
```

### 4.5. Состояние (`BurnStore` / `ThrottleStore`)

| Интерфейс | Назначение | Реализация в поставке |
|---|---|---|
| `BurnStore` | сожжённые ячейки (`CELL`), метки cooldown (`COOLDOWN`), использованные челленджи | `InMemoryBurnStore` |
| `ThrottleStore` | последовательные неудачи, lockout по `cardId` | `InMemoryThrottleStore` |

`InMemory*` — для **одного узла**; состояние теряется при рестарте. Для кластера используйте
готовые модули **`cotp-redis`** (`RedisBurnStore`/`RedisThrottleStore`, Lettuce) и **`cotp-jdbc`**
(`JdbcBurnStore`/`JdbcThrottleStore` + `JdbcStores.createSchema`); общий контракт — модуль
`cotp-store-contract`. Атомарность обеспечивают `markCellBurned`, `markChallengeUsed`,
`incrementFailure`. Периодическая эвикция: `purge(now)` / `purge(now, idleTtl)`.

```java
BurnStore burnStore = new InMemoryBurnStore();
ThrottleStore throttleStore = new InMemoryThrottleStore();
CotpChallengeGenerator gen = new CotpChallengeGenerator(policy, eventSink);
CotpVerifier verifier = new CotpVerifier(policy, burnStore, throttleStore, eventSink);
```

`Challenge` помечается использованным **при любой** `BurnPolicy` (включая `NONE`) — это защита от
replay конкретного челленджа.

---

## 5. Политика по контексту: `CotpPolicyResolver`

`CotpPolicyResolver` выбирает `CotpPolicy` по строковому контексту, разделяя burn/throttle-состояние
и схему карты. `null`/неизвестный контекст → default.

```java
Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicyResolver.defaults());
CotpChallenge ch = cotp.challenge(card, "kiosk_login", now);
VerificationResult r = cotp.authenticate(card, pinHash, ch, word, pin, now); // политика из ch.context()
```

Готовые пресеты `CotpPolicyResolver.defaults()`:

| Контекст | BurnPolicy | TTL | Комментарий |
|---|---|---|---|
| `kiosk_login` | `COOLDOWN` (24 ч) | 120 с | частые входы на публичной стойке |
| `stepup` | `CHALLENGE` | 60 с | частый step-up спец. разделов |
| `service_desk` | `CELL` | 120 с | редко, высокая чувствительность |
| `null` / неизвестный | `CELL` | 120 с | default |

Кастомный resolver:

```java
Map<String, CotpPolicy> byContext = Map.of(
    "kiosk_login", CotpPolicy.builder().burnPolicy(BurnPolicy.COOLDOWN)
        .cellCooldown(Duration.ofHours(24)).challengeTtl(Duration.ofSeconds(120)).build(),
    "stepup", CotpPolicy.builder().burnPolicy(BurnPolicy.CHALLENGE)
        .challengeTtl(Duration.ofSeconds(60)).build());
CotpPolicyResolver resolver =
    CotpPolicyResolver.of(CotpPolicy.defaults(), byContext);   // default для null/unknown
// либо: CotpPolicyResolver.fixed(policy);  // одна политика на все контексты
Cotp cotp2 = new Cotp(CotpScheme.defaults(), resolver);
```

**Ключевые инварианты политики** (нарушение → `CotpConfigurationException`):

- `TIME_BASED` **несовместим** с `BurnPolicy.CELL`/`COOLDOWN`; при `TIME_BASED` без явной политики
  по умолчанию подставляется `CHALLENGE`.
- `challengeTtl`, `cellCooldown`, `lockDuration` > 0; `pinIterations ≥ 1000` (default 210 000);
  `maxFailures ≥ 1` (default 5), `lockDuration` default 15 мин.
- Для `TIME_BASED` TTL автоматически ограничен остатком окна:
  `min(challengeTtl, (issueStep + timeWindowBack + 1)·step − now)`.

---

## 6. Внешние ответы: `VerificationResult` → `ClientError` (anti-oracle)

`VerificationResult` (`OK`, `INVALID_WORD`, `INVALID_PIN`, `IDENTITY_MISMATCH`, `CHALLENGE_EXPIRED`,
`CHALLENGE_REUSED`, `CELL_ALREADY_USED`, `CARD_EXPIRED`, `CARD_EXHAUSTED`, `THROTTLED`,
`INVALID_REQUEST`) — **внутренний**: аудит/метрики/UX. Наружу отдавайте только `ClientError`:

```java
ClientError client = r.toClientError();
```

| `ClientError` | Из `VerificationResult` | Сообщение (`genericMessage()`) |
|---|---|---|
| `NONE` (успех) | `OK` | OK |
| `AUTHENTICATION_FAILED` | `INVALID_WORD`, `INVALID_PIN`, `IDENTITY_MISMATCH`, `CHALLENGE_REUSED`, `CELL_ALREADY_USED`, `CARD_EXHAUSTED` | «Authentication failed» |
| `CHALLENGE_EXPIRED` | `CHALLENGE_EXPIRED` | «Challenge expired» |
| `CARD_EXPIRED` | `CARD_EXPIRED` | «Card expired» |
| `TOO_MANY_ATTEMPTS` | `THROTTLED` | «Too many attempts…» |
| `BAD_REQUEST` | `INVALID_REQUEST` | «Bad request» |

**[НОРМА]** Все фактор-специфичные отказы схлопываются в единый `AUTHENTICATION_FAILED`, чтобы
клиент не мог различить, какой фактор не совпал (§11.7). `client.isSuccess()` — `true` только для
`NONE`.

---

## 7. Наблюдаемость

### 7.1. Аудит-события ядра

```java
CotpEventSink sink = event -> audit.record(
        event.type(), event.at(), event.cardId(), event.context(),
        event.mode(), event.cellId(), event.result());
Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), sink);
```

`CotpEvent(type, at, cardId, context, mode, cellId, result)`. События **не содержат** секретов,
слов, PIN и `challengeId`; допускается публичный `cardId` (sink может хэшировать/опускать).
`context` — свободный контекст приложения (`kiosk_login`, `stepup`, …), проставляется при выдаче
челленджа и переносится в события верификации.

| `CotpEventType` | Когда | Поля |
|---|---|---|
| `CHALLENGE_ISSUED` | выдана ячейка | `mode`, `cellId`, `context` |
| `VERIFY_SUCCEEDED` | успешная проверка | `result=OK` |
| `VERIFY_FAILED` | отказ (см. `result`) | `result` |
| `CHALLENGE_REUSED_ATTEMPT` | replay `challengeId` | `result=CHALLENGE_REUSED` |
| `CARD_EXHAUSTED` | нет доступных ячеек | `result=CARD_EXHAUSTED` |
| `CELL_BURNED` | ячейка сожжена (`CELL`) | `cellId` |
| `CELL_COOLDOWN_RECORDED` | ячейка на cooldown (`COOLDOWN`) | `cellId` |
| `THROTTLE_LOCKOUT` | блокировка после серии неудач | `context` |

**[НОРМА]** sink обязан быть потокобезопасным, быстрым и не логировать секреты. По умолчанию —
`CotpEventSink.NOOP`.

### 7.2. Метрики (`cotp-metrics`)

Модуль подписывается на те же события и публикует Micrometer-метры (Prometheus-ready). Ядро при
этом не получает зависимостей:

```java
MeterRegistry registry = /* PrometheusMeterRegistry и т.п. */;
CotpMetrics metrics = CotpMetrics.create(registry, "5x5");
Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicy.defaults(), metrics.eventSink());

var verifier   = metrics.verifier(cotp.verifier());               // гистограммы латентности
var generator  = metrics.cardGenerator(cotp.cardGenerator());
var challenges = metrics.challengeGenerator(cotp.challengeGenerator());
```

Ключевые метры: `cotp_challenge_issued_total{mode,context}`, `cotp_verification_total{result,context}`,
`cotp_verification_duration_seconds{context,result}`, `cotp_cell_burned_total{layout}`,
`cotp_cards_exhausted_total{layout}`, `cotp_challenge_reuse_total`, `cotp_pin_verify_failures_total{context}`,
`cotp_throttle_lockouts_total{context}`, `cotp_identity_mismatch_total{context}`,
`cotp_enrollment_total{layout}`, `cotp_reissue_total{reason}`, `cotp_revocation_total{reason}`,
`cotp_secret_decrypt_failures_total`, `cotp_active_cards{layout}`, `cotp_collision_nonce{layout}`,
`cotp_challenge_ttl_seconds`, `cotp_operations_total{operation}`.

**[НОРМА]** В labels **нет** `card_id`/`employee_id`/`challenge_id`/`word`/`pin`/`secret`.
Подробности и PromQL — в [`cotp-metrics/README.md`](cotp-metrics/README.md) и runbook §10.

---

## 8. REST-эскиз

```text
POST /cotp/enroll            (admin/HR)
  req:  { "employeeId": "emp-42" }
  resp: { "cardId": "COTP-...", "cardPdfUrl": "/cards/COTP-....pdf", "expiresAt": "..." }
        # card_secret / grid НЕ возвращаются наружу

POST /cotp/challenge
  req:  { "employeeId": "emp-42", "context": "kiosk_login" }
        # для step-up: "context": "stepup:finance"
  resp: { "challengeId": "K3F...", "cell": "C2", "mode": "RANDOM",
          "expiresAt": "2026-10-06T17:09:03Z" }

POST /cotp/verify
  req:  { "challengeId": "K3F...", "word": "patient", "pin": "1234" }
  resp: 200 { "result": "OK", "stepUpToken": "..." }
        401 { "result": "AUTHENTICATION_FAILED" }   # ClientError.genericMessage()
```

Серверный обработчик `verify` (схематично):

```java
VerificationResult r = cotp.authenticate(card, pinHash, challenge, word, pin, Instant.now());
ClientError client = r.toClientError();              // наружу — только client
if (client == ClientError.NONE) {
    return ok(stepUpTokenFor(employeeId));
}
int status = switch (client) {
    case BAD_REQUEST -> 400;
    case TOO_MANY_ATTEMPTS -> 429;
    case CHALLENGE_EXPIRED, CARD_EXPIRED, AUTHENTICATION_FAILED -> 401;
    case NONE -> 200;
};
return error(status, client.genericMessage());       // детальный r — только в аудит/метрики
```

Правила: `challengeId` одноразовый и живёт TTL (§3.3); `cardId`/`employeeId` не раскрывать в
ошибках enum-различием; всё по TLS; детальные `VerificationResult` — только во внутренний аудит.

---

## 9. Анти-паттерны и требования безопасности

- **Не хранить и не логировать** сетку, `card_secret` (в т.ч. Base32-бэкап), слова, PIN и
  `challengeId` (события и метрики — без них). `CotpSecret.destroy()` — best-effort zeroization.
- **TLS 1.2+** обязателен: иначе челлендж и ответ перехватываются; COTP не phishing-resistant.
- **Не использовать клиентское время** для TTL/`TIME_BASED` — только серверные `Instant`
  (инъекция времени нужна для тестов). Серверные часы — по NTP.
- **`BurnPolicy.CELL` не подходит для частых входов** (десятки/день): 25 слотов ≈ 25 входов, затем
  re-enroll. Для частых сценариев — `COOLDOWN`/`CHALLENGE`.
- **Не изобретать крипто-JS**: вся проверка серверная; клиент лишь читает слово и шлёт `word+pin`
  по TLS. Тонкий REST-клиент/виджет — максимум.
- **Не отдавать детальный `VerificationResult`** наружу — только `toClientError()` (anti-oracle).
- **Не смешивать хранилища**: фасад `Cotp` создаёт собственные in-memory stores; для общих/кластерных
  используйте `CotpVerifier`/`CotpChallengeGenerator` с общими store.
- **Не менять словарь/раскладку/label «на месте»** — это инвалидирует все выданные карты. Любое
  изменение = новая `CotpScheme.version()` + перевыпуск.
- **Не переиспользовать `card_secret`** при re-enroll; новый `card_id` + новый секрет +
  `reset(cardId)`.
- **Identity anchor**: если приложение обязано его проверять — передавайте непустые значения с обеих
  сторон (fail-closed). `IDENTITY_MISMATCH` намеренно не влияет на throttle PIN.
- **Не полагаться на `InMemory*Store` в кластере**: нужны атомарные распределённые реализации.

---

## 10. FAQ

**Как обрабатывать `CARD_EXHAUSTED`?**
При `BurnPolicy.CELL`, если `burnedCells(cardId).size() >= scheme.slotCount()`:
- `verify` возвращает `VerificationResult.CARD_EXHAUSTED` (проверяется **до** `CELL_ALREADY_USED`);
- `challenge`/`generator.random(...)` бросает `CardExhaustedException` (а при `COOLDOWN` — когда все
  ячейки в окне cooldown).
Действие: немедленный re-enroll (§3.5). При `COOLDOWN` можно дождаться `cellCooldown`. Профилактика —
подобрать `BurnPolicy` под сценарий (§5).

**Нужен ли JS-клиент? Нужен ли крипто-JS?**
Нет. Вся криптография и проверка — на сервере; у клиента нет секрета. Достаточно **тонкого REST-клиента**
и виджета (показать ячейку, ввести слово+PIN, таймер TTL, generic-ошибка). Крипто-JS не нужен.

**Правда ли, что наблюдатель может восстановить сетку?**
Да — COTP это look-up secret: каждая наблюдённая пара (ячейка, слово) раскрывает значение ячейки;
собрав до `slotCount` различных ячеек, наблюдатель восстанавливает сетку (но ему всё ещё нужен PIN).
Контрмеры: `BurnPolicy.CELL`/`COOLDOWN`, короткий срок жизни, ротация, мониторинг аномалий, в
перспективе — multi-cell (2 ячейки на операцию, `TODO.md` P3-C). Это фундаментально для карточных
схем, не дефект реализации.

**Как поддерживать несколько версий схем?**
Храните `schemeId` в `CotpCredential` (кодек его пишет), регистрируйте все версии в `SchemeRegistry`
и восстанавливайте карту `cred.toCard(registry)`. Верификация всегда идёт по `card.scheme()`, поэтому
карта старой версии проверяется корректно. Смена словаря/раскладки = новая версия + перевыпуск.

**Где хранить PIN-хэш?**
Отдельно от `card_secret` (§18.5), в БД приложения. Проверка — `Cotp.verifyPin(pin, pinHash)` или
через `authenticate`/`verifier().verify(...)`.

**Можно ли использовать `TIME_BASED`?**
Да, но только с `BurnPolicy.CHALLENGE`/`NONE` (не `CELL`/`COOLDOWN`). TTL ограничивается окном
дрейфа; ячейка проверяется по `[nowStep − timeWindowBack, nowStep + timeWindowForward]`.

---

## 11. Ссылки

- [`COTP_SPECIFICATION.md`](COTP_SPECIFICATION.md) — полная спецификация (v1.0).
- [`examples/IntegrationExample.java`](examples/IntegrationExample.java) — компилируемый сквозной пример.
- [`cotp-java/README.md`](cotp-java/README.md) — обзор библиотеки и сборка.
- [`cotp-metrics/README.md`](cotp-metrics/README.md) — метрики Micrometer/Prometheus.
- [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) — модель угроз (STRIDE, look-up secret).
- [`docs/OPERATIONS_RUNBOOK.md`](docs/OPERATIONS_RUNBOOK.md) — эксплуатация: enrollment/re-enroll/revoke,
  хранение секретов, инциденты, конфигурация.
- [`docs/SEQUENCE_DIAGRAMS.md`](docs/SEQUENCE_DIAGRAMS.md) — mermaid-диаграммы сценариев.
- [`TODO.md`](TODO.md) — план развития (P1-A/P1-B, P2-G, P2-H, P3-C и др.).
