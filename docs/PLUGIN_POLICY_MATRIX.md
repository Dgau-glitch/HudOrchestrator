# HudOrchestrator Policy Matrix (ArtifactItems / QuestCore / noUseItem)

Цель: закрепить предсказуемое поведение очереди по вашему правилу приоритетов.

## 1) Бизнес-приоритеты (по вашему ТЗ)

Чем **ближе к 1**, тем **важнее**:

1. `QuestCore` — приоритет **1** (самый высокий)
2. `ArtifactItems` — приоритет **2**
3. `noUseItem` — приоритет **4** (самый низкий)

Для `HudRequestMeta.priority` (где больше число = выше приоритет) используйте отображение:

- `QuestCore` → `90`
- `ArtifactItems` → `75`
- `noUseItem` → `25`

## 2) Ключевые правила поведения очереди

### Правило A: QuestCore всегда сверху
- Все важные сценарии `QuestCore` отправлять с `PREEMPT` и приоритетом `90`.
- Для непрерывных потоков `QuestCore` использовать `COALESCE + stickinessTicks` (6–10), чтобы другие не «вклинивались» и не вызывали мигание.

### Правило B: ArtifactItems выше noUseItem
- Основной поток `ArtifactItems` — приоритет `75`.
- Для режима/критических событий — `PREEMPT`.
- Для частых апдейтов — `COALESCE`, чтобы не забивать канал.

### Правило C: noUseItem только когда канал свободен
- Использовать только `DROP_IF_BUSY`.
- Приоритет `25`.
- Никакого `PREEMPT` для `noUseItem`.

## 3) Рекомендованная матрица (ActionBar)

| Плагин | Сценарий | Policy | Priority | stickinessTicks | dedupKey |
|---|---|---|---:|---:|---|
| QuestCore | Прогресс/статус квеста | COALESCE | 90 | 8 | `quest:progress:<questId>` |
| QuestCore | Чекпоинт/важный этап | PREEMPT | 90 | 8 | `quest:checkpoint:<questId>` |
| ArtifactItems | Кулдаун артефакта | COALESCE | 75 | 6 | `artifact:cooldown:<artifactId>` |
| ArtifactItems | Смена режима/крит. событие | PREEMPT | 75 | 6 | `artifact:mode:<artifactId>` |
| noUseItem | Запрет действия | DROP_IF_BUSY | 25 | 0 | `nouse:blocked-action` |

## 4) TITLE и SCOREBOARD

### TITLE
- `QuestCore` completion/title: `PREEMPT`, `priority=90`.
- `ArtifactItems` title (если используется): `PREEMPT`, `priority=75`.
- `noUseItem` в `TITLE` не использовать.

### SCOREBOARD
- Постоянный владелец scoreboard: `ArtifactItems` (`ownerMode=true`, `priority=75`).
- Временные критичные scoreboard-события `QuestCore`: `PREEMPT`, `priority=90`.

## 5) Готовые примеры метаданных

### QuestCore (без мигания, всегда сверху)
```kotlin
HudRequestMeta(
    sourceId = "QuestCore:progress",
    priority = 90,
    policy = DeliveryPolicy.COALESCE,
    dedupKey = "quest:progress:<questId>",
    replaceGroup = "quest:progress",
    ttlTicks = 30,
    minShowTicks = 10,
    maxShowTicks = 30,
    sourceCooldownTicks = 2,
    stickinessTicks = 8
)
```

### ArtifactItems (выше noUseItem)
```kotlin
HudRequestMeta(
    sourceId = "ArtifactItems:totem-cooldown",
    priority = 75,
    policy = DeliveryPolicy.COALESCE,
    dedupKey = "artifact:cooldown:totem",
    replaceGroup = "artifact:cooldown",
    ttlTicks = 30,
    minShowTicks = 10,
    maxShowTicks = 30,
    sourceCooldownTicks = 2,
    stickinessTicks = 6
)
```

### noUseItem (только если очередь свободна)
```kotlin
HudRequestMeta(
    sourceId = "noUseItem:restrictions",
    priority = 25,
    policy = DeliveryPolicy.DROP_IF_BUSY,
    dedupKey = "nouse:blocked-action",
    replaceGroup = "nouse:block",
    ttlTicks = 24,
    minShowTicks = 8,
    maxShowTicks = 24,
    sourceCooldownTicks = 2
)
```

## 6) Чек-лист интеграции

1. Все `QuestCore`-потоки проверить на `priority=90`.
2. Все `ArtifactItems`-потоки проверить на `priority=75`.
3. Все `noUseItem`-потоки перевести на `DROP_IF_BUSY` + `priority=25`.
4. Для частых потоков (Quest/Artifact) включить `COALESCE`.
5. Для потоков, где важна стабильность, включить `stickinessTicks`.
6. Проверить в метриках, что `noUseItem` чаще всего дропается при занятости — это ожидаемое поведение по ТЗ.

## 7) Связанный документ

- API и базовые примеры: [`docs/API.md`](./API.md)
