# COTP — диаграммы последовательностей

Дополняет `COTP_SPECIFICATION.md` (§16) и `docs/OPERATIONS_RUNBOOK.md`. Схемы отражают реальный
API библиотеки: `Cotp`, `CotpEnrollment`, `CotpCredential`, `CotpPolicyResolver`,
`VerificationResult`, `ClientError`, `CotpEventSink`.

Общие принципы:
- Сервер (backend) — единственная доверенная зона; печатная карта и публичная стойка — недоверенные.
- Все обмены «клиент ↔ сервер» — по TLS.
- Детальный `VerificationResult` остаётся внутри; наружу отдаётся `ClientError` (anti-oracle, §11.7).
- Каждая выдача челленджа/проверка эмитит событие в `CotpEventSink` (аудит/метрики, §16.8).

## 1. Enrollment (выпуск карты)

```mermaid
sequenceDiagram
    autonumber
    participant HR as IT/HR (admin)
    participant APP as Backend (Cotp)
    participant CIPHER as SecretCipher / KMS
    participant DB as Card store
    participant USER as Сотрудник

    HR->>APP: cotp.enroll(cardId, pin)
    APP->>APP: generateCard(cardId) → CotpCard (grid 5×5)
    APP->>APP: new PinHasher(iters).hash(pin) → pinHash
    APP-->>HR: CotpEnrollment{ card, pinHash, credential }
    HR->>USER: печать card.grid() (без PII) + выдача
    HR->>CIPHER: encrypt(credentialEncoded())
    CIPHER-->>HR: sealed bytes
    HR->>DB: save(cardId, sealedCredential, pinHash, status=ACTIVE)
    Note over APP: событие аудита: enrollment
```

## 2. Вход на публичной стойке (RFID + COTP)

```mermaid
sequenceDiagram
    autonumber
    participant U as Сотрудник
    participant K as Киоск (неттоп)
    participant ID as Identity (RFID→employeeId)
    participant APP as Backend (Cotp)
    participant EV as CotpEventSink (SIEM/metrics)

    U->>K: прикладывает RFID-пропуск (фактор 1)
    K->>ID: badge UID
    ID-->>APP: employeeId (identity anchor)
    APP->>APP: find active card, decrypt credential (SecretCipher)
    APP->>APP: cotp.challenge(card, "kiosk_login", now)
    APP->>EV: CHALLENGE_ISSUED (mode, cellId, context)
    APP-->>K: { challengeId, cell: "C2", expiresAt }
    K-->>U: показать ячейку C2 + таймер TTL
    U->>K: читает слово с карты + вводит PIN
    K->>APP: authenticate(card, pinHash, challenge, word, pin, now)
    APP->>APP: Проверка: TTL → burn/cooldown → throttle → слово (constant-time) → PIN
    APP->>EV: VERIFY_* (+CELL_BURNED / CELL_COOLDOWN_RECORDED / THROTTLE_LOCKOUT)
    APP-->>K: VerificationResult (внутр.)
    K-->>U: ClientError (наружу: NONE / AUTHENTICATION_FAILED / TOO_MANY_ATTEMPTS …)
    Note over APP: успех → issuance сессии/токена
```

## 3. Step-up для защищённого раздела (независимо от способа входа)

```mermaid
sequenceDiagram
    autonumber
    participant U as Пользователь (уже в сессии)
    participant APP as Backend (Cotp)
    participant EV as CotpEventSink

    U->>APP: запрос защищённого раздела
    %% policy: CHALLENGE, TTL 60 c
    APP->>APP: cotp.challenge(card, "stepup", now)
    APP->>EV: CHALLENGE_ISSUED
    APP-->>U: { challengeId, cell }
    U->>APP: authenticate(card, pinHash, challenge, word, pin, now)
    APP->>EV: VERIFY_* (context=stepup)
    alt ClientError.NONE
        APP-->>U: короткоживущий step-up-токен (scope = раздел)
    else ошибка
        APP-->>U: ClientError (AUTHENTICATION_FAILED / TOO_MANY_ATTEMPTS …)
    end
```

## 4. Service desk — верификация личности

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller (сотрудник)
    participant AG as Агент service desk
    participant APP as Backend (Cotp)
    participant EV as CotpEventSink

    C->>AG: запрос восстановления доступа (по телефону)
    %% policy: CELL, TTL 120 c
    AG->>APP: cotp.challenge(card, "service_desk", now)
    APP->>EV: CHALLENGE_ISSUED
    APP-->>AG: { challengeId, cell }
    AG->>C: «Назовите слово из ячейки C2 и ваш PIN»
    C-->>AG: слово + PIN
    AG->>APP: authenticate(card, pinHash, challenge, word, pin, now)
    APP->>EV: VERIFY_* (+CELL_BURNED)
    APP-->>AG: VerificationResult → ClientError
    alt NONE
        AG->>C: разрешить восстановление (proof-first, затем смена пароля)
        Note over AG,APP: результат передаётся в аудит, карта/ячейка сожжена (CELL)
    else AUTHENTICATION_FAILED
        AG->>C: отказ, эскалация по политике
    end
```

## 5. Ревокация / перевыпуск карты

```mermaid
sequenceDiagram
    autonumber
    participant SEC as Security/Admin
    participant APP as Backend (Cotp)
    participant DB as Card store
    participant ST as BurnStore / ThrottleStore

    %% статус REVOKED
    SEC->>APP: revoke(cardId)
    APP->>DB: card.status = REVOKED
    %% очистить сожжённые ячейки и challenge-записи
    APP->>ST: reset(cardId)
    Note over APP: событие аудита: revocation
    SEC->>APP: re-enroll (новый cardId + новый card_secret)
    %% старое secret больше не валидно
    APP->>DB: save(new cardId, sealedCredential, pinHash)
```

## Примечания

- `"kiosk_login"` / `"stepup"` / `"service_desk"` — контексты `CotpPolicyResolver` (спека §16.9):
  `kiosk_login → COOLDOWN 24 ч`, `stepup → CHALLENGE 60 с`, `service_desk → CELL`.
- Burn/cooldown/throttle-семантика — `docs/OPERATIONS_RUNBOOK.md`, спека §11.3–11.4.
- Модель угроз и остаточные риски (в т.ч. реконструкция сетки при наблюдении) — `docs/THREAT_MODEL.md`.
