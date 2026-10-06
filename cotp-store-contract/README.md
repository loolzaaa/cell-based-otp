# cotp-store-contract — контрактные тесты хранилищ COTP

Небольшой вспомогательный модуль с **переиспользуемыми JUnit 5 контрактами** для реализаций
`BurnStore` и `ThrottleStore`. Любая (в т.ч. внешняя/распределённая) реализация может унаследовать
эти абстрактные классы и получить единый набор проверок вместо копирования тестов.

- Координаты: `com.cotp:cotp-store-contract:1.0.0` (зависит от `com.cotp:cotp-java:1.0.0` и
  `org.junit.jupiter:junit-jupiter-api`; сам тестов не запускает — классы абстрактные).
- Назначение: **test-support**; в прод-артефакты не попадает.

## Классы

- `com.cotp.store.contract.AbstractBurnStoreContract` — контракт `BurnStore`.
- `com.cotp.store.contract.AbstractThrottleStoreContract` — контракт `ThrottleStore`.

### Что проверяется (`BurnStore`)

одноразовость ячейки и челленджа (атомарный check-and-set), снимок сожжённых ячеек, cooldown-таймстемпы
(`lastCellUse`/`recordCellUse`), `reset(cardId)` (очищает ячейки/time/челленджи карты) и изоляция карт.

### Что проверяется (`ThrottleStore`)

инкремент счётчика неудач, `lockUntil`/`lockedUntil`, `reset(cardId)`.

Тайминг `evictExpired`/`evictIdle` намеренно **не** входит в общий контракт (best-effort; для
TTL-нативных хранилищ — no-op) и проверяется отдельно у конкретной реализации.

## Использование

```java
class MyBurnStoreTest extends AbstractBurnStoreContract {
    @Override protected BurnStore newStore() {
        return new MyBurnStore(/* ... */);   // свежее, пустое хранилище на каждый тест
    }
}
```

Обеспечьте **изоляцию состояния между тестами**: возвращайте новое пустое хранилище (отдельная схема,
БД, префикс ключей и т.п.), иначе тесты будут влиять друг на друга.

## Сборка

```powershell
# 1. установить ядро в локальный репозиторий
cotp-java\mvnw.cmd -B -f cotp-java\pom.xml install
# 2. установить контракт (нужен как test-зависимость потребителям)
cotp-java\mvnw.cmd -B -f cotp-store-contract\pom.xml install
```

Потребители (примеры): [`cotp-jdbc`](../cotp-jdbc) и [`cotp-redis`](../cotp-redis).

Лицензия: MIT.
