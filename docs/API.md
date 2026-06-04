# HudOrchestrator API (актуальная версия)

Документ для интеграторов Paper/Spigot/Purpur: как безопасно отправлять `ACTION_BAR`, `TITLE`, `SCOREBOARD` без конфликтов между плагинами.

## 1. Получение сервиса

```kotlin
val registration = server.servicesManager.getRegistration(HudOrchestratorApi::class.java)
val hud = registration?.provider ?: return
```

> Рекомендуется получать сервис в `onEnable()` и обновлять ссылку после reload-зависимостей.

## 2. Каналы и контракты

- `ACTION_BAR` — частые короткие апдейты (кулдауны/прогресс).
- `TITLE` — редкие важные события.
- `SCOREBOARD` — структурированный HUD-блок.

`submit*` возвращает `HudHandle?`:
- `handle != null` — запрос принят в оркестратор;
- `null` — backpressure/policy reject/rate-limit/overflow.

## 3. Threading (важно)

В актуальной версии `HudOrchestratorService` безопасно обрабатывает off-thread вызовы `submit*`: запрос маршалится на main thread.

Для явной и предсказуемой интеграции всё равно рекомендуется использовать:

```kotlin
hud.submitActionBarThreadSafe(plugin, player.uniqueId, request)
hud.submitTitleThreadSafe(plugin, player.uniqueId, request)
hud.submitScoreboardThreadSafe(plugin, player.uniqueId, request)
```

## 4. ActionBar: рекомендуемый профиль

```kotlin
val request = ActionBarRequest(
    content = Component.text("Квест: 3/10"),
    meta = HudRequestMeta(
        sourceId = "QuestCore:progress",
        priority = Priority.HIGH.weight,
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

val handle = hud.submitActionBar(player.uniqueId, request)
if (handle == null) {
    // backpressure: уменьшите частоту или увеличьте sourceCooldownTicks
}
```

## 5. Title: критичные события

```kotlin
val request = TitleRequest(
    title = Component.text("Квест завершен!"),
    subtitle = Component.text("+500 XP"),
    fadeInTicks = 10,
    stayTicks = 40,
    fadeOutTicks = 10,
    meta = HudRequestMeta(
        sourceId = "QuestCore:completion",
        priority = Priority.CRITICAL.weight,
        policy = DeliveryPolicy.PREEMPT,
        dedupKey = "quest:complete:main_story",
        ttlTicks = 80,
        minShowTicks = 40,
        maxShowTicks = 80,
        sourceCooldownTicks = 10
    )
)

val handle = hud.submitTitle(player.uniqueId, request)
```

Практика:
- `PREEMPT` только для реально важных событий.
- Не завышайте `stayTicks`, чтобы не блокировать канал.

## 6. Scoreboard: owner-mode

```kotlin
val request = ScoreboardRequest(
    title = Component.text("§6Артефакт"),
    lines = listOf(
        Component.text("§7Режим: §fЛесоруб"),
        Component.text("§7КД: §f12с"),
        Component.text("§7Энергия: §f78%")
    ),
    ownerMode = true,
    meta = HudRequestMeta(
        sourceId = "ArtifactItems:scoreboard",
        priority = Priority.HIGH.weight,
        policy = DeliveryPolicy.ENQUEUE,
        dedupKey = "artifact:sb:owner",
        ttlTicks = 200,
        minShowTicks = 40,
        maxShowTicks = 200,
        sourceCooldownTicks = 20
    )
)

hud.submitScoreboard(player.uniqueId, request)
```

Ограничения:
- максимум 15 строк;
- `ownerMode=true` для устойчивого «владельца» scoreboard.

## 7. Политики доставки

- `ENQUEUE` — обычная очередь.
- `PREEMPT` — вытеснение текущего при достаточном приоритете.
- `COALESCE` — схлопывание потока по `dedupKey/replaceGroup`.
- `DROP_IF_BUSY` — не мешать занятому каналу.

## 8. Приоритеты и starvation control

Рекомендуемые диапазоны:
- LOW: `20..35`
- NORMAL: `45..60`
- HIGH: `65..80`
- CRITICAL: `85+`

Оркестратор учитывает aging (рост эффективного приоритета по времени ожидания), чтобы снизить starvation.

## 9. Rate-limit и backpressure

На игрока+источник применяются:
- token bucket (`capacity`, `refill-per-second`),
- `sourceCooldownTicks`.

При `null` от `submit*`:
1. снижайте частоту генерации;
2. переводите поток на `COALESCE`;
3. корректируйте `sourceCooldownTicks`;
4. проверяйте `rate-limit.*` в `config.yml`.

## 10. sourceId (обязательно стабильный)

Формат:
- `PluginName`
- `PluginName:subsystem`

Примеры:
- `NoUseItem:restrictions`
- `ArtifactItems:totem-cooldown`
- `QuestCore:progress`

Это нужно для корректного cleanup при disable/reload плагина.

## 11. Метрики и наблюдаемость

```kotlin
val m = hud.metricsSnapshot()
logger.info("submitted=${m.submitted}, rejected=${m.rejectedByRateLimit}, dropped=${m.droppedByPolicy}")
```

Основные счётчики:
- `submitted`
- `rejectedByRateLimit`
- `droppedByPolicy`
- `replacedByCoalesce`
- `queueOverflowDropped`
- `preemptions`


## 11.1 Source overrides из `config.yml` (приоритет выше, чем в плагинах)

HudOrchestrator теперь применяет `source-overrides` по маске `sourceId` (`*` поддерживается).

Порядок применения:
1. Плагин отправляет `HudRequestMeta` со своими параметрами.
2. HudOrchestrator ищет первое совпадение в `source-overrides`.
3. Совпавшие поля переопределяют значения из плагина.

Переопределяемые поля:
- `priority`
- `policy`
- `source-cooldown-ticks`
- `stickiness-ticks`
- `dominance-ticks`

Важно: если совпадения нет, используются настройки из самого плагина (его `HudRequestMeta`).

Пример:
```yaml
source-overrides:
  questcore:
    pattern: "QuestCore:*"
    priority: 90
    policy: PREEMPT
    dominance-ticks: 10
```

## 11.2 Hard dominance window (`dominanceTicks`)

`dominanceTicks` — отдельный режим в `HudRequestMeta` для ActionBar.

Если источник отправил сообщение с `dominanceTicks > 0`, то на это окно
блокируются кандидаты с более низким приоритетом. Это убирает “мигание”
низкоприоритетных fallback-сообщений поверх доминирующего потока.


## 11.3 Строгая защита приоритета для `DROP_IF_BUSY`

Для `ACTION_BAR` низкоприоритетный запрос с `DROP_IF_BUSY` теперь не только не вытесняет активный HUD, но и не попадает в очередь, если есть активный, sticky/dominance или pending-кандидат с более высоким `priority`.

Практический эффект: `NoUseItem` при спаме не должен отправляться в Minecraft ActionBar API поверх `ArtifactItems`/`QuestCore`; он будет отброшен как fallback, пока высокоприоритетный источник активен или ожидает показа. Для сценариев, которые должны дождаться очереди, используйте не `DROP_IF_BUSY`, а `ENQUEUE` или `COALESCE`.

## 12. Async пример end-to-end

```kotlin
plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
    val dto = loadQuestStateFromStorage(player.uniqueId)

    val request = TitleRequest(
        title = Component.text("Новая цель"),
        subtitle = Component.text(dto.targetName),
        meta = HudRequestMeta(
            sourceId = "QuestCore:async-sync",
            priority = Priority.HIGH.weight,
            policy = DeliveryPolicy.COALESCE,
            dedupKey = "quest:async:${dto.questId}",
            ttlTicks = 60,
            minShowTicks = 20,
            maxShowTicks = 60
        )
    )

    hud.submitTitleThreadSafe(plugin, player.uniqueId, request)
        .thenAccept { handle ->
            if (handle == null) plugin.logger.fine("HudOrchestrator backpressure for ${player.uniqueId}")
        }
})
```

## 13. Операционный checklist

1. У каждого сценария фиксированный `sourceId`.
2. Частые потоки используют `COALESCE + dedupKey`.
3. Вторичные события — `DROP_IF_BUSY`.
4. `PREEMPT` только для high/critical.
5. Мониторинг `metricsSnapshot()` на пиковом онлайне.
6. `debug.queue-logging` включать только на диагностику.

## 14. Смежный документ

- Матрица профилей: [`docs/PLUGIN_POLICY_MATRIX.md`](./PLUGIN_POLICY_MATRIX.md)
