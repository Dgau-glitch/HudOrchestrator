# HudOrchestrator API (актуальная версия)

Документ для интеграторов Folia/Paper-совместимых серверов: как безопасно отправлять `ACTION_BAR`, `TITLE`, `SCOREBOARD` без конфликтов между плагинами.

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

В Folia нет единого "main thread" для операций над игроком. `HudOrchestratorService` сам переносит `submit*`-мутации на `player.scheduler` конкретного online-игрока. Если игрок offline или его entity scheduler retired, submit вернёт `null`.

Рекомендуемый современный вариант — async API без прямой зависимости интегратора от Bukkit/Folia scheduler:

```kotlin
hud.submitActionBarAsync(player.uniqueId, request)
hud.submitTitleAsync(player.uniqueId, request)
hud.submitScoreboardAsync(player.uniqueId, request)
```

Legacy helpers `submit*ThreadSafe(plugin, ...)` оставлены для совместимости, но теперь просто делегируют в `submit*Async(...)`; планирование остаётся ответственностью реализации HudOrchestrator.

### Player-local ticks

Внутри сервиса TTL, cooldown, `minShowTicks`, `maxShowTicks`, `stickinessTicks` и `dominanceTicks` считаются не через глобальный `Bukkit.getCurrentTick()`, а через локальный счётчик `PlayerHudState`. Он увеличивается только per-player repeating task этого игрока. Это соответствует Folia-модели, где регионы тикают независимо, и исключает зависимость HUD-очереди игрока от глобального tick counter.

### Cleanup lifecycle

`clearPlayer`, quit/kick cleanup и shutdown не вызывают визуальный restore напрямую из произвольного потока. Если у сервиса есть ссылка на online `Player`, восстановление scoreboard планируется через `player.scheduler`; если entity scheduler retired, внутренние maps/tasks очищаются без обращения к player API.

### Admin reload на Folia

`/hudorchestrator reload` не перезапускает сервис прямо из command handler. Команда планирует reload на Folia `globalRegionScheduler`: сначала отменяются player tasks текущего сервиса, затем внутреннее состояние очищается, config перечитывается и регистрируется новый `HudOrchestratorApi`. Сообщения игроку-администратору отправляются через его `player.scheduler`; console feedback отправляется на global scheduler.

`plugin.yml` содержит `folia-supported: true`, потому что submit/render/cleanup/reload пути больше не зависят от единого Bukkit main thread.

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

`ownerMode=true` делает scoreboard устойчивым владельцем канала: после dispatch он не истекает по `maxShowTicks`, а сервис каждый tick проверяет, что HUD scoreboard всё ещё назначен игроку. Если другой плагин временно поменял `player.scoreboard`, HudOrchestrator пере-применит свою доску на entity thread игрока. Для обновлений того же источника используйте тот же `sourceId` и `COALESCE`/`dedupKey`: same-source обновления активного scoreboard принимаются как refresh и не блокируются source cooldown.

Folia-важно: renderer не вызывает `ScoreboardManager#getNewScoreboard()`, потому что на Folia этот путь может бросать `UnsupportedOperationException`. Вместо этого HudOrchestrator использует текущий `player.scoreboard` и управляет только своим objective `hud_orchestrator` и временными team/entry-строками. Поэтому для production лучше направлять все sidebar-scoreboard потоки через HudOrchestrator, чтобы другие плагины не перетирали тот же display slot или objective. При очистке канала сервис удаляет свой objective/строки на entity thread игрока.

Для постоянных scoreboard-владельцев используйте `ownerMode=true` + `COALESCE` + стабильный `dedupKey`/`replaceGroup`. Такой поток считается state-refresh, поэтому HudOrchestrator не применяет к нему `sourceCooldownTicks`: иначе первый пустой/промежуточный кадр или частые retry могли бы оставить игрока без sidebar до следующего cooldown window. Backpressure всё равно остаётся через coalesce, max queue size и priority-owner rules.

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

HudOrchestrator теперь применяет `source-overrides` по маске `sourceId` (`*` поддерживается, например `ArtifactItems:*`).

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

Если источник отправил или поставил в очередь сообщение с `dominanceTicks > 0`, HudOrchestrator держит защитное окно для этого приоритета.
Низкоприоритетные кандидаты блокируются не только во время показа, но и между обновлениями доминирующего потока. Это убирает “мигание”
низкоприоритетных fallback-сообщений поверх `ArtifactItems`/`QuestCore`.


## 11.3 Строгая защита приоритета для `DROP_IF_BUSY`

Для `ACTION_BAR` низкоприоритетный запрос с `DROP_IF_BUSY` теперь не только не вытесняет активный HUD, но и не попадает в очередь, если есть активный, sticky/dominance или pending-кандидат с более высоким `priority`.

Практический эффект: `NoUseItem` при спаме не должен отправляться в Minecraft ActionBar API поверх `ArtifactItems`/`QuestCore`; он будет отброшен как fallback, пока высокоприоритетный источник активен, ожидает показа или недавно принял новый ActionBar-запрос. `DROP_IF_BUSY` fallback также не диспатчится мгновенно в момент submit: он ждёт стабильное idle-окно `action-bar.fallback-idle-grace-ticks`, чтобы высокоприоритетные запросы того же/следующих тиков успели вытеснить/удалить его без миллисекундного flash. Для сценариев, которые должны дождаться очереди, используйте не `DROP_IF_BUSY`, а `ENQUEUE` или `COALESCE`.

## 11.4 Stable idle grace для fallback ActionBar

`action-bar.fallback-idle-grace-ticks` в `config.yml` задаёт минимальное количество тиков, в течение которых ActionBar должен оставаться стабильно свободным перед показом `DROP_IF_BUSY` fallback-сообщения.

Рекомендуемое значение для `NoUseItem` рядом с частыми потоками `ArtifactItems`: `6..10` тиков. Чем выше значение, тем меньше шанс flash, но тем реже fallback покажется в коротких паузах.

## 12. Async пример end-to-end

```kotlin
CompletableFuture.supplyAsync {
    loadQuestStateFromStorage(player.uniqueId)
}.thenCompose { dto ->

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

    hud.submitTitleAsync(player.uniqueId, request)
}.thenAccept { handle ->
    if (handle == null) plugin.logger.fine("HudOrchestrator backpressure for ${player.uniqueId}")
}
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
