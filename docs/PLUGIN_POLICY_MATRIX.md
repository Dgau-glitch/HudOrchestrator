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
- На Folia не используйте fallback через `ScoreboardManager#getNewScoreboard()`: HudOrchestrator рендерит sidebar на текущем `player.scoreboard` и владеет только objective `hud_orchestrator`. Если нужен стабильный sidebar без конфликтов, остальные плагины должны отдавать scoreboard-сообщения через HudOrchestrator или не трогать `DisplaySlot.SIDEBAR`.
- Для `ArtifactItems:scoreboard` держите `ownerMode=true`, `COALESCE` и стабильный `dedupKey`: HudOrchestrator трактует такие заявки как refresh постоянного owner-state и не режет их `sourceCooldownTicks`, чтобы scoreboard не пропадал из-за retry/первичного кадра.

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


## 6.1 Что сильнее: настройки плагина или `source-overrides`?

Ответ: **`source-overrides` в HudOrchestrator сильнее**.

- Плагин отправляет свои `HudRequestMeta`.
- Затем HudOrchestrator накладывает первое совпавшее правило `source-overrides` по `sourceId`/маске.
- Совпавшие поля заменяются значениями из оркестратора.

Это сделано специально для централизованного контроля приоритетов/политик в проде без релиза каждого плагина.


## 6.2 Почему `noUseItem` больше не должен мигать поверх ArtifactItems

`noUseItem` должен использовать `DROP_IF_BUSY` и низкий `priority=25`. При такой комбинации HudOrchestrator считает его fallback-сообщением: если сейчас активен/доминирует/ожидает или только что был принят источник с более высоким приоритетом (`ArtifactItems=75`, `QuestCore=90`), запрос `noUseItem` отбрасывается и не отправляется в Minecraft ActionBar API. Дополнительно fallback-запросы `DROP_IF_BUSY` ждут стабильное idle-окно `action-bar.fallback-idle-grace-ticks` перед dispatch, поэтому high-priority обновления, пришедшие в ближайшие тики, удалят их без миллисекундного flash. Для `ArtifactItems:*` рекомендуется `dominance-ticks: 20`, чтобы закрывать короткие паузы между частыми обновлениями артефактов.

Если какому-то низкоприоритетному сценарию нужно именно дождаться очереди, а не быть отброшенным, для него надо выбрать `ENQUEUE` или `COALESCE`, но не `DROP_IF_BUSY`.

## 7) Связанный документ

- API и базовые примеры: [`docs/API.md`](./API.md)
