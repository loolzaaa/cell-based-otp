# COTP — runbook эксплуатации

> **Назначение.** Операционные процедуры жизненного цикла COTP-карт: выпуск, перевыпуск, отзыв,
> ротация, хранение секретов, управление состоянием (burn/cooldown/throttle/TTL), реагирование на
> инциденты, мониторинг и точные параметры конфигурации.
> **Статус.** Сверен с `COTP_SPECIFICATION.md` (v1.0) и кодом `cotp-java/` 1.0.0. Пункты, которых
> ещё нет в коде, помечены **[ПЛАН]** со ссылкой на `TODO.md`.
> **Язык:** русский; идентификаторы, классы, конфиги, RFC — в латинице. **Не логировать секреты/PII.**
> **Дата:** 2026-10-06.

---

## 0. Область и предпосылки

- Объект эксплуатации: серверный компонент `COTP Service` + печатные карты + клиенты киоска.
- Хранилище состояния burn/throttle и БД карт — ответственность интегратора (ядро их не хранит).
- Для кластера требуются распределённые `BurnStore`/`ThrottleStore` **[ПЛАН]** (`TODO.md` P2-G);
  в поставке есть только `InMemoryBurnStore`/`InMemoryThrottleStore`.
- События/метрики для аудита и алертов **[ПЛАН]** (`TODO.md` P1-A, P1-B).

> **Терминология.** `card_secret` — секрет карты; `card_id` — публичный id (HKDF salt, печатается);
> `K_grid`/`K_challenge` — производные ключи; `slot`/`cell` — ячейка сетки; «burn» — необратимое
> использование. Схема карты = `CotpScheme` (версия/словарь/раскладка/время), политика =
> `CotpPolicy` (режим/TTL/burn/throttle/PIN).

---

## 1. Роли и ответственность

| Роль | Обязанности |
|---|---|
| Issuer (HR/IT) | Выпуск и выдача карты, сброс PIN, перевыпуск |
| Security/Admin | Отзыв, ротация, управление KMS/HSM, инциденты |
| Auditor | Просмотр аудита/SIEM, расследования |
| SRE/On-call | Мониторинг, алерты, эвикция состояния, доступность KMS |

Разделение привилегий (issuer/approver/auditor) — требование §20 спецификации.

---

## 2. Жизненный цикл карты (обзор)

```
enroll ─► print ─► issue ─┬─► challenge/verify ─┬─► OK (burn/cooldown)
                          │                      └─► fail (throttle)
                          ├─► CARD_EXHAUSTED ──► re-enroll
                          ├─► expiresAt ───────► re-enroll
                          └─► loss/copy ───────► revoke ──► re-enroll
```

Управление статусом `ACTIVE|REVOKED|EXPIRED` и модель данных `COTP_CARD`, `COTP_BURN`,
`COTP_CHALLENGE_USED`, `COTP_THROTTLE`, `COTP_AUDIT` — **на стороне приложения** (§16.6).
Библиотека предоставляет только `CotpCard.expiresAt()` (проверяется в `CotpVerifier` как
`CARD_EXPIRED`); отдельного API статуса/отзыва нет.

---

## 3. Enrollment (первичный выпуск)

**Процедура:**

1. Сгенерировать публичный id: `String cardId = cotp.newCardId();` — 80-бит Base32 (CSPRNG).
2. Сгенерировать карту: `CotpCard card = cotp.generateCard(cardId);`
   — новый `card_secret` (по умолчанию 256 бит) и детерминированная сетка.
3. Задать PIN: `String pinHash = cotp.newPinHash(pin.toCharArray());`
4. **Зашифровать секрет at-rest**: `String encSecret = cipher.encryptToBase64(card.secret().copyBytes());`
   (`AesGcmSecretCipher` или KMS-адаптер **[ПЛАН]**).
5. Сохранить: `cardId`, `encSecret`, `pinHash`, `scheme.version()` (и раскладку/словарь),
   `employeeId`, `createdAt`, `expiresAt`. **Сам `card_secret` наружу/в ответ API не отдавать**
   (§16.5).
6. **Напечатать сетку без PII**: `CotpCardPrinter.render(card)` (ASCII) либо внешний PDF-шаблон
   (iText/PDFBox/OpenPDF — вне библиотеки, **[ПЛАН]** U-C). На карте: `card_id`, версия, даты,
   краткая инструкция; **никаких** ФИО/табельного/PIN (§12.2, §15).
7. Выдать карту владельцу; **PIN сообщить отдельным каналом** и не печатать на карте.
8. Зарегистрировать аудит `enrollment` **[ПЛАН]** (P1-A).

**Проверка корректности сетки** (опционально, после загрузки секрета):
`new CotpCardGenerator(scheme).derive(loadedCard)` — при несовпадении сетки бросается `CotpException`
(признак повреждения/неверного секрета/несогласованной схемы).

> **Внимание.** Для нестандартной раскладки/словаря использовать
> `new Cotp(cotpScheme, cotpPolicy)`; для TOP/BOTTOM (50 слов) —
> `CotpScheme.builder().positions(List.of(CellPosition.TOP, CellPosition.BOTTOM)).build()`.

---

## 4. Re-enrolment (перевыпуск)

**Триггеры:**

- `VerificationResult.CARD_EXHAUSTED` (при `BurnPolicy.CELL` все ячейки сожжены);
- `CardExhaustedException` из `CotpChallengeGenerator` (в т.ч. `COOLDOWN`: все ячейки в окне
  `cellCooldown`);
- истечение `expiresAt`;
- утеря/копирование/подозрение на компрометацию;
- плановая ротация (§6).

**Процедура:**

1. Сгенерировать **новые** `cardId` и `card_secret` (никогда не переиспользовать секрет).
2. Выполнить шаги enrollment (§3), выдать новую карту.
3. Отозвать старую карту (§5).
4. **Очистить состояние**: `burnStore.reset(oldCardId)`; `throttleStore.reset(oldCardId)`.
   `BurnStore.reset(cardId)` очищает и сожжённые ячейки, и использованные челленджи карты (§11.2).
5. Зарегистрировать аудит `reissue(reason)` **[ПЛАН]** (P1-A, `cotp_reissue_total`).

---

## 5. Revocation (отзыв)

1. Пометить `COTP_CARD.status = REVOKED` в БД приложения (у библиотеки статуса нет).
2. Прекратить выдачу челленджей по этому `cardId`.
3. `burnStore.reset(cardId)`, `throttleStore.reset(cardId)` — очистка состояния.
4. По политике: сохранить `encSecret` для аудита либо уничтожить; после уничтожения ключа карта
   невосстановима (потребуется re-enroll).
5. Зарегистрировать аудит `revocation(reason)` **[ПЛАН]** (`cotp_revocation_total`).

> **Немедленность.** Отзыв и перевыпуск — секунды (нужен только `card_secret`; сетка
> регенерируется), это ключевое преимущество COTP (§1.3, §16.4).

---

## 6. Политика ротации

| Параметр | Значение | Источник |
|---|---|---|
| Срок жизни карты | 180–365 дней | §16.7 |
| Плановая ротация | например раз в 90 дней | §16.7 |
| Перевыпуск = | **новый** `cardId` + **новый** `card_secret` + `BurnStore.reset` | §16.4, §16.7 |
| Смена словаря/раскладки/label | **смена `version`** (`CotpScheme`) и перевыпуск **всех** карт | §9.4, §13.3 |

**Правила:**

- Менять раскладку/словарь/label/порядок слотов «на месте» **запрещено** — только новая версия
  `CotpScheme` (§9.4, Лемма 1/Следствие 3).
- Карта проверяется по **своей** `card.scheme()` (§11.2), поэтому при смене версии нужно
  сохранять/резолвить нужную схему по каждой карте; `SchemeRegistry` **[ПЛАН]** (P2-A).
- Частоту ротации выбирать по burn-политике и сценарию (§16.7, таблица «сценарий → BurnPolicy»).

---

## 7. Хранение секретов и управление ключами

### 7.1. `SecretCipher` (шифрование at-rest)

Интерфейс: `encrypt(byte[])`, `decrypt(byte[])`, `encryptToBase64`, `decryptFromBase64`.

Реализация в поставке — `AesGcmSecretCipher`:

| Свойство | Значение |
|---|---|
| Алгоритм | AES-GCM (`AES/GCM/NoPadding`) |
| Длина ключа | 16, 24 или 32 байта (рекомендуется 32 = AES-256) |
| IV | свежий случайный 96-бит (12 байт) на **каждое** шифрование |
| Тег | 128 бит |
| Формат | `IV (12 B) ‖ ciphertext+tag` |
| `generate()` | эфемерный ключ (только тесты/демо) |
| `keyBytes()` | копия ключа для обёртки/ротации в KMS/HSM |

### 7.2. KMS/HSM

- Рекомендуется: ключ AES хранится в KMS/HSM и инжектируется при старте, **либо** операция
  делегируется в KMS через отдельный адаптер `SecretCipher` **[ПЛАН]** (P2-H; AWS KMS / Azure Key
  Vault / HashiCorp Vault).
- Ядро остаётся zero-dependency; адаптер — отдельный опциональный модуль (§12.3).
- Доступ к KMS — PoLP, аудит операций `Encrypt`/`Decrypt`.

### 7.3. Ротация ключей

- **Ключ AES (`AesGcmSecretCipher`)**: при ротации выполнить перешифрование `card_secret`:
  расшифровать старым ключом, зашифровать новым (окно dual-key), затем вывести старый. Встроенного
  инструмента нет — на стороне приложения/KMS.
- **`card_secret`**: ротация = перевыпуск карты (§4, §6). Никогда не переиспользовать секрет.
- **PIN**: не является ключом; при смене `pinIterations` старые `pin_hash` продолжают проверяться
  (итерации берутся из закодированной строки), авто-апгрейд — **[ПЛАН]** (P2-E).

### 7.4. Базовые правила

- **[НОРМА]** Base32-представление `card_secret` конфиденциально; **не** печатать (§5.3).
- Не логировать `card_secret`, base32-бэкап, слово и PIN; best-effort zeroization —
  `CotpSecret.destroy()`.
- `pin_hash` хранить отдельно от `card_secret` (§18.5).

---

## 8. Хранилища состояния

### 8.1. `BurnStore` — одноразовость

| Операция | Смысл | Атомарность |
|---|---|---|
| `isCellBurned(cardId, cellId)` | ячейка сожжена (`CELL`) | чтение |
| `markCellBurned(cardId, cellId)` | сжечь ячейку; `true` если впервые | **check-and-set** |
| `lastCellUse` / `recordCellUse` | метка последнего использования (`COOLDOWN`) | чтение/запись |
| `isChallengeUsed(challengeId)` | челлендж уже потреблён | чтение |
| `markChallengeUsed(cardId, challengeId, expiresAt)` | потреблять челлендж; привязка к карте + TTL | **check-and-set** |
| `burnedCells(cardId)` | снимок сожжённых ячеек | чтение |
| `reset(cardId)` | очистить ячейки **и** использованные челленджи карты | — |
| `evictExpired(now)` | best-effort эвикция (default no-op) | — |

> `challengeId` помечается использованным **при любой** `BurnPolicy` (в т.ч. `NONE`) — защита от
> replay конкретного челленджа (§11.3).

### 8.2. `ThrottleStore` — защита PIN

| Операция | Смысл |
|---|---|
| `failureCount(cardId)` | число последовательных неудач |
| `lockedUntil(cardId)` | время разблокировки (`null` если не заблокирован) |
| `lockUntil(cardId, until)` | явная блокировка |
| `incrementFailure(cardId)` | атомарный инкремент, возвращает новое значение |
| `reset(cardId)` | сброс (после успеха / истечения блокировки) |
| `evictIdle(now, idleTtl)` | best-effort эвикция idle-состояния (default no-op) |

> Счётчик привязан к `cardId` и **не сбрасывается новой сессией** (§11.4, RFC 4226 §7.3).
> `IDENTITY_MISMATCH` **не** инкрементирует счётчик (§11.2).

### 8.3. Семантика политик burn (`BurnPolicy`)

| Политика | Что сжигается | Ёмкость | Совместимость |
|---|---|---|---|
| `CELL` (default) | Ячейка + челлендж | `slotCount` успехов, затем re-enroll | **Не** с `TIME_BASED` |
| `COOLDOWN` | Только челлендж; ячейка недоступна `cellCooldown` | не ограничена | **Не** с `TIME_BASED` |
| `CHALLENGE` | Только челлендж | не ограничена | ок (в т.ч. `TIME_BASED`) |
| `NONE` | Только челлендж; ячейки не сжигаются | не ограничена | только тесты/stateless `TIME_BASED` |

### 8.4. TTL-эвикция (`purge` / плановая задача)

- `InMemoryBurnStore` и `InMemoryThrottleStore` **сами** вызывают `evictExpired`/`evictIdle` из
  горячего пути (`CotpVerifier.verify`), но прореженно (`EVICT_GATE = 1024`).
- Для ограничения памяти вызвать **безусловную** очистку из плановой задачи:
  - `InMemoryBurnStore.purge(Instant now)` — удаляет использованные челленджи с `expiry <= now`;
  - `InMemoryThrottleStore.purge(Instant now, Duration idleTtl)` — удаляет незаблокированное
    состояние, не использованное дольше `idleTtl`.
- Рекомендуемый период: 5–15 минут (согласовать с `challengeTtl` и `lockDuration`).
- TTL записи челленджа = `challenge.expiresAt()`; в распределённом store использовать нативный TTL
  **[ПЛАН]** (P2-G).
- `ThrottleStore.evictIdle` вызывается в верификаторе с `idleTtl = policy.lockDuration()`
  (в текущей реализации).

### 8.5. Что делать при `CARD_EXHAUSTED`

- **`verify` → `CARD_EXHAUSTED`**: при `BurnPolicy.CELL` `burnedCells(cardId).size() >= slotCount`.
  Проверяется **до** `CELL_ALREADY_USED` (§11.2).
- **`challenge` → `CardExhaustedException`**: `CELL` — все ячейки сожжены; `COOLDOWN` — все ячейки
  в окне cooldown (сообщение «all cells are within the cooldown window»).
- **Действия:** при `CELL` — немедленный re-enroll (§4); при `COOLDOWN` — можно подождать
  `cellCooldown` либо перевыпустить; зафиксировать аудит `CARD_EXHAUSTED` **[ПЛАН]**.
- **Профилактика:** подобрать `BurnPolicy` под сценарий (§16.7); для частых входов — `COOLDOWN`/
  `CHALLENGE`; для строгого one-time — `CELL`.

---

## 9. Инциденты (incident response)

### 9.1. Подозрение на копирование карты / наблюдение

1. Отозвать и перевыпустить затронутую карту (§4, §5), `reset` состояния.
2. Сохранить и изучить аудит/SIEM: серии челленджей, аномальные `context`/IP.
3. Ужесточить политику: перейти на `CELL`, сократить срок/ротацию, включить обязательный PIN.
4. Рассмотреть **multi-cell** для высокоценных операций **[ПЛАН]** (P3-C, §18.2).
5. Если возможно, сбросить/повысить требования к PIN.

### 9.2. Утеря/кража карты

1. Немедленный отзыв (§5) и перевыпуск (§4).
2. PIN не менять обязательно (не на карте), но при подозрении компрометации — сменить.
3. Identity anchor (RFID) сохраняется.

### 9.3. Массовые lockouts

1. Различить **атаку** (много неудач по разным/одной картам) и **сбой конфигурации/часов**.
2. Проверить `failureCount`/`lockedUntil`; при легитимном пользователе — разблокировать
   `throttleStore.reset(cardId)` либо дождаться `lockDuration` (по умолчанию 15 мин).
3. При переборе: активировать внешний rate-limit **[ПЛАН]** (P2-F), ужесточить `maxFailures`/
   `lockDuration`, ограничить по IP/контексту (не в ядре).
4. Изучить аудит; при необходимости временно отключать вход с рискованных точек.
5. **Не** полагаться на `IDENTITY_MISMATCH` для блокировки: он намеренно не влияет на throttle.

### 9.4. `SECRET_DECRYPT_FAILURE`

> **Статус.** Событие `SECRET_DECRYPT_FAILURE` и метрика `cotp_secret_decrypt_failures_total`
> **[ПЛАН]** (`TODO.md` P1-A/P1-B) **ещё не реализованы**. Сейчас ошибка проявляется как
> `CotpException` из `AesGcmSecretCipher.decrypt` («AES-GCM decryption failed (wrong key or
> tampered ciphertext)»).

1. **Не** повторять вслепую; проверить версию/целостность ключа шифрования (ошибка ротации/конфига).
2. Проверить, не ротирован ли ключ без перешифрования (dual-key окно).
3. Если ключ безвозвратно утерян — карта **невосстановима**: re-enroll (§4).
4. Настроить алерт на всплеск таких исключений на уровне приложения (до появления P1-B).
5. Исключить tampering БД; проверить аудит доступа.

### 9.5. Компрометация секрета (все карты)

1. Считать скомпрометированными **все** `card_secret`; при возможности — ротировать мастер-ключ
   AES (§7.3).
2. **Перевыпустить все карты** с новыми `card_id`+`card_secret`; `reset` состояния.
3. Если `pin_hash` хранились отдельно и не утекли — PIN можно сохранить; иначе принудительная смена.
4. Инвалидировать активные сессии/step-up токены; усилить мониторинг.
5. Оценить, утёк ли только шифротекст (при сохранности ключа AES ущерб меньше) — решение принимать
   по данным KMS-аудита.

### 9.6. Смена словаря/схемы/версии

1. Ввести **новую `version`** в `CotpScheme`; выпускать новые карты под неё.
2. Поддерживать старую версию в резолвере схем до полного вывода старых карт **[ПЛАН]** (P2-A,
   `SchemeRegistry`).
3. `COTP_CARD.scheme_version`/`layout` хранить по каждой карте (§16.6).

### 9.7. Дрейф часов (`TIME_BASED`)

- Ячейка `TIME_BASED` принимается, только если совпадает с детерминированным выбором для любого шага
  в окне `[nowStep − timeWindowBack, nowStep + timeWindowForward]` (§11.2).
- При ошибках — проверить NTP; расширять окно осторожно (снижает защиту).
- TTL `TIME_BASED` автоматически ограничен `min(challengeTtl, timeWindowBack · timeStepSeconds)`.

---

## 10. Мониторинг и алерты

### 10.1. Аудит-события (**[ПЛАН]** P1-A)

`CotpEventSink` (no-op по умолчанию) должен эмитить: `CHALLENGE_ISSUED`, `VERIFY_OK`,
`VERIFY_FAIL(result)`, `CELL_BURNED`, `CELL_COOLDOWN`, `LOCKOUT`, `CHALLENGE_REUSED_ATTEMPT`,
`CARD_EXHAUSTED`, `CARD_EXPIRED`, `IDENTITY_MISMATCH`, `SECRET_DECRYPT_FAILURE`.
**Требование:** события без секретов/PII.

### 10.2. Метрики Prometheus (**[ПЛАН]** P1-B, модуль `cotp-metrics`)

Ключевые метрики: `cotp_challenge_issued_total`, `cotp_verification_total{result}`,
`cotp_verification_duration_seconds`, `cotp_cell_burned_total`, `cotp_cards_exhausted_total`,
`cotp_challenge_expired_total`, `cotp_challenge_reuse_total`, `cotp_pin_verify_failures_total`,
`cotp_throttle_lockouts_total`, `cotp_identity_mismatch_total`, `cotp_enrollment_total`,
`cotp_reissue_total`, `cotp_revocation_total`, `cotp_secret_decrypt_failures_total`,
`cotp_active_cards`, `cotp_collision_nonce`. **В labels нет** `card_id`/`employee_id`/
`challenge_id`/`word`/`pin`.

Примерные алерты (PromQL, из `TODO.md` P1-B):

```promql
sum(rate(cotp_pin_verify_failures_total[5m])) > 0.5          # перебор
increase(cotp_throttle_lockouts_total[15m]) > 5               # лок-ауты
increase(cotp_challenge_reuse_total[5m]) > 0                  # replay
increase(cotp_cards_exhausted_total[1h]) > 20                 # исчерпание
histogram_quantile(0.95, sum(rate(cotp_verification_duration_seconds_bucket[5m])) by (le)) > 1
increase(cotp_challenge_issued_total[10m]) > 200              # попытка «выучить» карты
```

### 10.3. SIEM / аудит

- Модель `COTP_AUDIT(id, employee_id, card_id, event, result, ts, ip, context)` (§16.6).
- До реализации P1-A промежуточно: логировать на уровне приложения **без** секретов/PII/слов/PIN.
- Аномалии «по конкретной карте» — в аудит/SIEM (не в Prometheus из-за кардинальности).
- Эндпоинт метрик не выставлять в интернет.

---

## 11. Справочник конфигурации

### 11.1. `CotpScheme` (схема карты; влияет на сетку)

| Поле билдера | По умолчанию | Ограничения |
|---|---|---|
| `version` | `"COTP/v1"` | не пустой, без `\|`, без пробельных/format-символов |
| `dictionary` | BIP-39 English (2048) | size — степень двойки, 256…65536, слова уникальны без учёта регистра |
| `columns` | `A,B,C,D,E` | буквы `A–Z`, уникальные |
| `rows` | `1,2,3,4,5` | `>= 1`, уникальные |
| `positions` | `SINGLE` | `SINGLE`, либо `TOP,BOTTOM` (50 слотов) |
| `secretBytes` | `32` (256 бит) | `16…64` |
| `timeStepSeconds` | `30` | `>= 1` |
| `timeWindowBack` | `4` | `>= 0` |
| `timeWindowForward` | `1` | `>= 0` |

Инварианты: слотов `<= 4096` и `<= dictionary.size()`.

### 11.2. `CotpPolicy` (операционная политика)

| Поле билдера | По умолчанию | Ограничения / примечание |
|---|---|---|
| `challengeMode` | `RANDOM` | `TIME_BASED` без явной burn-политики → автоматически `CHALLENGE` |
| `challengeTtl` | `120 s` | `> 0` |
| `burnPolicy` | `CELL` | `CELL`/`COOLDOWN`/`CHALLENGE`/`NONE` |
| `cellCooldown` | `24 h` | `> 0`; только для `COOLDOWN` |
| `pinIterations` | `210 000` | `>= 1000` (OWASP baseline ≥ 210k) |
| `maxFailures` | `5` | `>= 1` (блокировка при достижении) |
| `lockDuration` | `15 min` | `> 0` |

**Инвариант:** `TIME_BASED` **несовместим** с `CELL`/`COOLDOWN` — `CotpPolicy` бросает
`IllegalArgumentException` при сборке; используйте `CHALLENGE` или `NONE`.

### 11.3. Соответствие деталей и механизмов

| Параметр | Где задаётся | Комментарий |
|---|---|---|
| Тайм-степ/окно времени | `CotpScheme` | Карта и верификатор берут из `card.scheme()` |
| TTL/burn/cooldown/throttle/PIN | `CotpPolicy` | Операционные, не влияют на вывод сетки |
| TTL `TIME_BASED` | Вычисляется | `min(challengeTtl, timeWindowBack · timeStepSeconds)` |
| PIN-хэш | `PinHasher` (через `CotpPolicy.pinIterations`) | Формат `pbkdf2-sha256$iters$salt$hash` |
| Секрет/бэкап | `CotpSecret` | Base32; `destroy()` — zeroization |
| Шифрование at-rest | `SecretCipher`/`AesGcmSecretCipher` | KMS-адаптер **[ПЛАН]** (P2-H) |

> **Фасад vs. хранилища.** `new Cotp(scheme, policy)` создаёт **собственные in-memory** stores.
> Для общих/распределённых хранилищ создавать `CotpVerifier(policy, burnStore, throttleStore)`
> напрямую (и `CotpChallengeGenerator(policy)`).

---

## 12. Чек-листы

### 12.1. Enrollment

- [ ] `cardId` сгенерирован CSPRNG; `card_secret` — CSPRNG, уникален.
- [ ] `card_secret` зашифрован at-rest (`SecretCipher`); в БД хранится только шифротекст.
- [ ] `pin_hash` (PBKDF2) сохранён; PIN передан отдельным каналом.
- [ ] `version`/`layout`/`employeeId`/`expiresAt` сохранены.
- [ ] Печать: только `card_id`, версия, даты, инструкция; **нет PII** и **нет PIN**.
- [ ] Аудит `enrollment` записан.
- [ ] Секрет не попал в логи/ответ API.

### 12.2. Ротация / re-enroll

- [ ] Новые `cardId` + `card_secret` (переиспользование исключено).
- [ ] Старая карта отозвана; `BurnStore.reset` и `ThrottleStore.reset` выполнены.
- [ ] Новая карта напечатана и выдана; PIN актуален.
- [ ] Аудит `reissue`/`revocation` записан.

### 12.3. Инцидент: компрометация

- [ ] Затронутые/все карты отозваны (§9.1/§9.5).
- [ ] `BurnStore`/`ThrottleStore` очищены.
- [ ] Мастер-ключ AES ротирован (при необходимости) — dual-key перешифрование.
- [ ] Сессии/step-up токены инвалидированы.
- [ ] Аудит/SIEM сохранён; уведомления по регламенту.
- [ ] Ужесточены `BurnPolicy`/срок ротации; рассмотрен multi-cell (P3-C).

### 12.4. Эксплуатация (регулярно)

- [ ] Проверен алерт на `cotp_secret_decrypt_failures_total` (или исключения decrypt).
- [ ] Плановая задача `purge`/`evictIdle` выполняется.
- [ ] NTP синхронизирован (для `TIME_BASED`/TTL).
- [ ] Резервные копии зашифрованных секретов и KMS-доступа проверены.
- [ ] Сроки карт (`expiresAt`) под контролем; ротация по расписанию.
- [ ] Доступ к KMS/аудиту ограничен (PoLP).

---

## Источники

Спецификация: `COTP_SPECIFICATION.md` — §5 (генерация/хранение секрета), §9.4 (контракт версии),
§10 (челлендж, TTL, `TIME_BASED`), §11 (верификация, burn-политики, throttling, гонки, результаты),
§12 (`PinHasher`, identity anchor, `SecretCipher`), §13 (словарь), §15 (формат карты без PII),
§16 (интеграция, enrollment/re-enroll/revocation, модель данных, политики), §18 (безопасность),
§20 (чек-лист внедрения), §21 (открытые вопросы); код `cotp-java/` (`Cotp`, `CotpScheme`,
`CotpPolicy`, `CotpVerifier`, `CotpChallengeGenerator`, `BurnStore`/`InMemoryBurnStore`,
`ThrottleStore`/`InMemoryThrottleStore`, `SecretCipher`/`AesGcmSecretCipher`, `PinHasher`,
`CotpSecret`, `CotpCardGenerator`, `CotpCardPrinter`); план — `TODO.md` (P1-A, P1-B, P2-A, P2-E,
P2-F, P2-G, P2-H, P3-A, P3-C, P3-E).
