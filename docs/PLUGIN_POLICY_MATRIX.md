# HudOrchestrator Policy Matrix (Production)

Детальная матрица для согласованной работы плагинов:

- `NoUseItem`
- `ArtifactItems`
- `QuestCore`

Документ ориентирован на высокий онлайн и минимизацию HUD-конфликтов.

---

## 1) Единые правила интеграции

1. Используйте стабильные `sourceId` формата `PluginName:subsystem`.
2. Для частых обновлений применяйте `COALESCE + dedupKey`.
3. `PREEMPT` — только для действительно критичных ситуаций.
4. Декоративные/вторичные уведомления — `DROP_IF_BUSY`.
5. Не отправляйте одинаковые сообщения каждый тик без coalesce.

---

## 2) Приоритетная шкала (рекомендуемая)

- **LOW**: 20–35
- **NORMAL**: 45–60
- **HIGH**: 65–80
- **CRITICAL**: 85+

Важно: числа ниже — рекомендации, не жёсткий контракт.

---

## 3) NoUseItem

### Основные сценарии

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---|---:|---:|---:|---:|---:|---|
| Запрет действия/использования | ACTION_BAR | DROP_IF_BUSY | 30 | 30 | 8 | 30 | 2 | `nouse:blocked-action` |
| Повторный триггер того же запрета | ACTION_BAR | COALESCE | 30 | 30 | 8 | 30 | 2 | `nouse:blocked-action` |

### `sourceId`
- `NoUseItem:restrictions`

### Пример

```kotlin
HudRequestMeta(
    sourceId = "NoUseItem:restrictions",
    priority = 30,
    policy = DeliveryPolicy.DROP_IF_BUSY,
    dedupKey = "nouse:blocked-action",
    ttlTicks = 30,
    minShowTicks = 8,
    maxShowTicks = 30,
    sourceCooldownTicks = 2
)
```

### Почему так
- Сообщение полезно, но не должно «перебивать» важный HUD от квестов/артефактов.
- `DROP_IF_BUSY` и low-priority защищают UX от визуального хаоса.

---

## 4) ArtifactItems

### Основные сценарии

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---|---:|---:|---:|---:|---:|---|
| Кулдаун артефакта (частый поток) | ACTION_BAR | COALESCE | 50 | 30 | 10 | 30 | 2 | `artifact:cooldown:<artifactId>` |
| Смена режима артефакта | ACTION_BAR | PREEMPT | 70 | 36 | 12 | 36 | 6 | `artifact:mode:<artifactId>` |
| Критичный отказ использования | ACTION_BAR | PREEMPT | 80 | 25 | 10 | 25 | 8 | `artifact:critical:<reason>` |
| Временный overlay scoreboard | SCOREBOARD | PREEMPT | 70 | 100 | 20 | 100 | 20 | `artifact:sb:overlay` |
| Постоянный owner scoreboard | SCOREBOARD | ENQUEUE | 55 | 200 | 40 | 200 | 20 | `artifact:sb:owner` |

### `sourceId`
- `ArtifactItems:totem-cooldown`
- `ArtifactItems:lumber-mode`
- `ArtifactItems:scoreboard`

### Пример: cooldown поток

```kotlin
HudRequestMeta(
    sourceId = "ArtifactItems:totem-cooldown",
    priority = 50,
    policy = DeliveryPolicy.COALESCE,
    dedupKey = "artifact:cooldown:totem",
    ttlTicks = 30,
    minShowTicks = 10,
    maxShowTicks = 30,
    sourceCooldownTicks = 2,
    stickinessTicks = 6
)
```

### Почему так
- Частый апдейт кулдауна без `COALESCE` быстро забивает ActionBar.
- `stickinessTicks` снижает однокадровые вклинивания чужих сообщений.

---

## 5) QuestCore

### Основные сценарии

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---|---:|---:|---:|---:|---:|---|
| Прогресс цели квеста | ACTION_BAR | COALESCE | 55 | 30 | 10 | 30 | 2 | `quest:progress:<questId>` |
| Важный этап/чекпоинт | ACTION_BAR | PREEMPT | 70 | 25 | 10 | 25 | 6 | `quest:checkpoint:<questId>` |
| Завершение квеста | TITLE | PREEMPT | 85 | 80 | 40 | 80 | 10 | `quest:complete:<questId>` |

### `sourceId`
- `QuestCore:progress`
- `QuestCore:checkpoint`
- `QuestCore:completion`

### Пример: completion title

```kotlin
HudRequestMeta(
    sourceId = "QuestCore:completion",
    priority = 85,
    policy = DeliveryPolicy.PREEMPT,
    dedupKey = "quest:complete:main_story",
    ttlTicks = 80,
    minShowTicks = 40,
    maxShowTicks = 80,
    sourceCooldownTicks = 10
)
```

### Почему так
- Завершение квеста — событие высокого приоритета, может временно вытеснить менее важный HUD.

---

## 6) Межплагинные конфликты и порядок важности

Рекомендуемый порядок в типичной RPG-сборке:

1. `QuestCore` completion TITLE (`85`) — самое важное пользовательское событие.
2. `ArtifactItems` mode switch/fail (`70–80`) — важно для моментального feedback.
3. `QuestCore` progress (`55`) и `ArtifactItems` cooldown (`50`) — рабочий фон.
4. `NoUseItem` blocked-action (`30`) — вторичный сигнал, не должен доминировать.

---

## 7) Рекомендуемые `replaceGroup`

Используйте `replaceGroup` для логически родственных потоков:

- `quest:progress`
- `artifact:cooldown`
- `artifact:mode`
- `nouse:block`

Это упрощает coalesce/замещение и сокращает шум очередей.

---

## 8) Готовые шаблоны (копипаст)

### NoUseItem: blocked action

```kotlin
ActionBarRequest(
    content = Component.text("Этот предмет сейчас использовать нельзя"),
    meta = HudRequestMeta(
        sourceId = "NoUseItem:restrictions",
        priority = 30,
        policy = DeliveryPolicy.DROP_IF_BUSY,
        dedupKey = "nouse:blocked-action",
        replaceGroup = "nouse:block",
        ttlTicks = 30,
        minShowTicks = 8,
        maxShowTicks = 30,
        sourceCooldownTicks = 2
    )
)
```

### ArtifactItems: cooldown

```kotlin
ActionBarRequest(
    content = Component.text("Тотем: 12с"),
    meta = HudRequestMeta(
        sourceId = "ArtifactItems:totem-cooldown",
        priority = 50,
        policy = DeliveryPolicy.COALESCE,
        dedupKey = "artifact:cooldown:totem",
        replaceGroup = "artifact:cooldown",
        ttlTicks = 30,
        minShowTicks = 10,
        maxShowTicks = 30,
        sourceCooldownTicks = 2,
        stickinessTicks = 6
    ),
    resendIntervalTicks = 10
)
```

### QuestCore: progress

```kotlin
ActionBarRequest(
    content = Component.text("Квест: 3/10"),
    meta = HudRequestMeta(
        sourceId = "QuestCore:progress",
        priority = 55,
        policy = DeliveryPolicy.COALESCE,
        dedupKey = "quest:progress:main_story",
        replaceGroup = "quest:progress",
        ttlTicks = 30,
        minShowTicks = 10,
        maxShowTicks = 30,
        sourceCooldownTicks = 2,
        stickinessTicks = 6
    ),
    resendIntervalTicks = 10
)
```

---

## 9) Таблица anti-patterns

| Анти-паттерн | Почему плохо | Как правильно |
|---|---|---|
| `PREEMPT` почти везде | постоянное вытеснение и «дребезг» HUD | оставить `PREEMPT` только для high/critical |
| случайные `sourceId` (UUID/timestamp) | невозможно clean-up и адекватный rate-limit | стабильный `PluginName:subsystem` |
| апдейты каждый тик без coalesce | рост очереди/отказы/мигание | `COALESCE + dedupKey` |
| игнор `null` из `submit*` | потерянные сообщения без контроля | считать `null` сигналом backpressure |

---

## 10) Валидация перед релизом

### Техническая
1. Проверить, что каждый сценарий имеет фиксированный `sourceId`.
2. Проверить, что frequent-потоки используют `COALESCE`.
3. Проверить, что есть fallback-политика для вторичных сообщений.
4. Проверить, что async-источники используют `submit*ThreadSafe`.

### Нагрузочная
1. Прогнать synthetic burst (массовые квест апдейты + cooldown + deny).
2. Снять `metricsSnapshot()` до/после.
3. Добиться `queueOverflowDropped == 0` на нормальном профиле.
4. Убедиться, что `rejectedByRateLimit` в ожидаемом диапазоне.

---

## 11) Связанные документы

- Полное API-руководство: [`docs/API.md`](./API.md)
