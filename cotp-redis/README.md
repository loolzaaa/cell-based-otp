# cotp-redis — Redis-хранилища состояния COTP

Опциональный модуль: реализации `BurnStore` и `ThrottleStore` поверх Redis (клиент **Lettuce**) для
**многонодовых** развёртываний. Ядро `cotp-java` остаётся без зависимостей.

- `com.cotp.redis.RedisBurnStore implements BurnStore, AutoCloseable`
- `com.cotp.redis.RedisThrottleStore implements ThrottleStore, AutoCloseable`

## Ключи (префикс по умолчанию `cotp:`)

```
burn:<card>:<cell>   -> "1"             SET NX (сожжённая ячейка)
burn:<card>          -> set<cell>       индекс для burnedCells/reset
use:<card>:<cell>    -> epochMilli      последнее использование (COOLDOWN)
use:<card>           -> set<cell>       индекс для reset
ch:<challengeId>     -> <card>          SET NX PX (использованный челлендж, нативный TTL)
ch:<card>            -> set<challengeId> индекс для reset
```

В Redis **нет** секретов, слов или PIN — только идентификаторы и таймстемпы.

## Использование

```java
RedisBurnStore burnStore = RedisBurnStore.create("redis://localhost:6379");
RedisThrottleStore throttleStore = RedisThrottleStore.create("redis://localhost:6379");

CotpVerifier verifier = new CotpVerifier(policy, burnStore, throttleStore, eventSink);
// ...
burnStore.close();
throttleStore.close();
```

Атомарность — `SET ... NX` (ячейки/челленджи) и `HINCRBY` (счётчик неудач). Челленджи истекают
нативным TTL Redis, поэтому `evictExpired` — no-op.

## Тестирование

Общий контракт `cotp-store-contract` прогоняется против **реального** Redis; при отсутствии сервера
тесты **пропускаются**:

```
set COTP_REDIS_URI=redis://localhost:6379
cotp-java\mvnw.cmd -B -f cotp-redis\pom.xml verify
```

Без `COTP_REDIS_URI` сборка зелёная, 9 тестов skipped (проверка на живом Redis — задача
эксплуатации/инфраструктуры, TODO P2-G «[ИНФРА]»). Каждый тест использует уникальный префикс
ключей для изоляции.

Лицензия: MIT (как и ядро).
