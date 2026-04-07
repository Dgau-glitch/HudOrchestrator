# HudOrchestrator policy/priority/TTL matrix for your plugins

Документ задает **готовые продакшен-профили** для интеграции:
- `NoUseItem`
- `ArtifactItems`
- `QuestCore`

Цель: минимальный визуальный конфликт между плагинами при высоком онлайне.

---

## Общие правила

- `sourceId` использовать в формате `PluginName:subsystem`.
- Для частых апдейтов всегда `COALESCE + dedupKey`.
- `PREEMPT` использовать только для действительно критичных сообщений.
- Для декоративных уведомлений — `DROP_IF_BUSY`.

---

## NoUseItem

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Запрет использования предмета / действия | ACTION_BAR | PREEMPT | 70 (HIGH) | 20 | 8 | 20 | 10 | `nouse:blocked-action` |
| Повторный спам тем же действием | ACTION_BAR | COALESCE | 50 (NORMAL) | 20 | 6 | 20 | 10 | `nouse:blocked-action` |

Пример `sourceId`:
- `NoUseItem:restrictions`

---

## ArtifactItems

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Кулдаун артефакта (частый) | ACTION_BAR | COALESCE | 50 (NORMAL) | 30 | 10 | 30 | 2 | `artifact:cooldown:<artifactId>` |
| Смена режима артефакта | ACTION_BAR | PREEMPT | 70 (HIGH) | 36 | 12 | 36 | 6 | `artifact:mode:<artifactId>` |
| Критичный fail/use denied | ACTION_BAR | PREEMPT | 80 | 25 | 10 | 25 | 8 | `artifact:critical:<reason>` |
| Временный HUD scoreboard-оверлей | SCOREBOARD | PREEMPT | 70 (HIGH) | 100 | 20 | 100 | 20 | `artifact:sb:overlay` |
| Постоянный artifact scoreboard owner | SCOREBOARD | ENQUEUE + ownerMode=true | 55 | 200 | 40 | 200 | 20 | `artifact:sb:owner` |

Примеры `sourceId`:
- `ArtifactItems:totem-cooldown`
- `ArtifactItems:lumber-mode`
- `ArtifactItems:scoreboard`

---

## QuestCore

| Сценарий | Channel | Policy | Priority | ttlTicks | minShowTicks | maxShowTicks | sourceCooldownTicks | dedupKey |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Прогресс цели квеста | ACTION_BAR | COALESCE | 55 | 30 | 10 | 30 | 2 | `quest:progress:<questId>` |
| Завершение квеста (title) | TITLE | PREEMPT | 85 | 80 | 40 | 80 | 10 | `quest:complete:<questId>` |
| Важный этап/чекпоинт | ACTION_BAR | PREEMPT | 70 | 25 | 10 | 25 | 6 | `quest:checkpoint:<questId>` |

Примеры `sourceId`:
- `QuestCore:progress`
- `QuestCore:completion`

---

## Рекомендации по конфликтам между этими 3 плагинами

1. `QuestCore` completion title всегда выше обычных actionbar-кулдаунов `ArtifactItems`.
2. `NoUseItem` запрет действия не должен бесконечно прерывать quest-progress:
   - ставить `sourceCooldownTicks >= 10`.
3. `ArtifactItems` frequent cooldown updates только через `COALESCE`, иначе забьет action bar.
4. Если одновременно нужен quest/actionbar и artifact/actionbar:
   - quest progress: `55`, artifact cooldown: `50`, mode switch: `70`.

---

## Готовые шаблоны метаданных

### NoUseItem (block action)
```kotlin
HudRequestMeta(
    sourceId = "NoUseItem:restrictions",
    priority = 70,
    policy = DeliveryPolicy.PREEMPT,
    dedupKey = "nouse:blocked-action",
    ttlTicks = 20,
    minShowTicks = 8,
    maxShowTicks = 20,
    sourceCooldownTicks = 10
)
```

### ArtifactItems (cooldown)
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

### QuestCore (progress)
```kotlin
HudRequestMeta(
    sourceId = "QuestCore:progress",
    priority = 55,
    policy = DeliveryPolicy.COALESCE,
    dedupKey = "quest:progress:<questId>",
    ttlTicks = 30,
    minShowTicks = 10,
    maxShowTicks = 30,
    sourceCooldownTicks = 2
)
```
