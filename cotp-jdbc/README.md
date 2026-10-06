# cotp-jdbc — JDBC-хранилища состояния COTP

Опциональный модуль: реализации `BurnStore` и `ThrottleStore` поверх JDBC `DataSource` для
**многонодовых** развёртываний (состояние one-time/cooldown/throttle разделяется через общую БД).
Ядро `cotp-java` остаётся без зависимостей.

- `com.cotp.jdbc.JdbcBurnStore implements BurnStore`
- `com.cotp.jdbc.JdbcThrottleStore implements ThrottleStore`
- `com.cotp.jdbc.JdbcStores` — DDL (`SCHEMA`, `createSchema(DataSource)`).

## Схема

Создайте таблицы (идемпотентно):

```java
JdbcStores.createSchema(dataSource);
```

Таблицы: `cotp_cell_burn`, `cotp_cell_use`, `cotp_challenge`, `cotp_throttle`. В них **нет** секретов,
слов или PIN — только `card_id`, `cell_id`/`challenge_id` и таймстемпы (epoch millis).

## Использование

```java
JdbcBurnStore burnStore = new JdbcBurnStore(dataSource);
JdbcThrottleStore throttleStore = new JdbcThrottleStore(dataSource);

CotpVerifier verifier = new CotpVerifier(policy, burnStore, throttleStore, eventSink);
CotpChallengeGenerator generator = new CotpChallengeGenerator(policy, eventSink);
```

## Атомарность

- `markCellBurned` / `markChallengeUsed` — `INSERT` по PK/unique; конфликт (SQLState `23…` /
  `SQLIntegrityConstraintViolationException`) означает «уже использовано» → `false`.
- `incrementFailure` — атомарный `UPDATE ... SET failures = failures + 1` (с insert-on-missing),
  конкурентные неудачи не теряются (проверено тестом на H2).
- `evictExpired(now)` — `DELETE ... WHERE expires_at <= now` (вызывайте по расписанию).

## Тестирование

Тесты используют встроенную **H2** (test scope) и общий контракт `cotp-store-contract`:

```
cotp-java\mvnw.cmd -B -f cotp-jdbc\pom.xml verify
```

## Совместимость СУБД

DDL написан на стандартном SQL и проверен на H2. Для MySQL < 8.0.29 может потребоваться убрать
`IF NOT EXISTS` у `CREATE INDEX`; форма таблиц и ограничения — контракт. Пул соединений/`DataSource`
предоставляет приложение.

Лицензия: MIT (как и ядро).
