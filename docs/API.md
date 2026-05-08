# HudOrchestrator API

Подробное руководство по интеграции с `HudOrchestratorApi` для Paper/Spigot/Purpur серверов.

---

## 1) Что делает сервис

`HudOrchestrator` централизует отправку HUD-контента в 3 канала:

- `ACTION_BAR`
- `TITLE`
- `SCOREBOARD`

Ключевая цель — убрать конфликт между плагинами, контролировать спам и обеспечить предсказуемое поведение под нагрузкой.

---

## 2) Подключение API

Сервис публикуется через Bukkit `ServicesManager`:

```kotlin
val registration = server.servicesManager.getRegistration(HudOrchestratorApi::class.java)
val hud = registration?.provider ?: return // HudOrchestrator не установлен/не активен
```

### Рекомендация
Всегда кешируйте `hud` после `onEnable`, но переинициализируйте ссылку после reload/restart внешней зависимости, если ваш плагин поддерживает hot-reload.

---

## 3) Базовые модели API

- `HudChannel` — канал вывода (`ACTION_BAR`, `TITLE`, `SCOREBOARD`).
- `DeliveryPolicy` — стратегия обработки конфликтов (`ENQUEUE`, `PREEMPT`, `COALESCE`, `DROP_IF_BUSY`).
- `HudRequestMeta` — метаданные управления очередью/приоритетом/ограничениями.
- `ActionBarRequest`, `TitleRequest`, `ScoreboardRequest` — payload по каналам.
- `HudHandle` — хэндл принятого запроса (для отмены/отслеживания).

---

## 4) Полный пример: ActionBar

```kotlin
val request = ActionBarRequest(
    content = Component.text("Квест обновлен: 3/10"),
    meta = HudRequestMeta(
        sourceId = "QuestCore:progress",
        priority = Priority.HIGH.weight,
        policy = DeliveryPolicy.COALESCE,
        dedupKey = "quest:progress:main_story",
        replaceGroup = "quest:progress",
        ttlTicks = 40,
        minShowTicks = 10,
        maxShowTicks = 40,
        sourceCooldownTicks = 2,
        stickinessTicks = 6
    ),
    resendIntervalTicks = 10
)

val handle = hud.submitActionBar(player.uniqueId, request)
if (handle == null) {
    // backpressure: rate-limit / policy drop / overflow
}
```

### Когда выбирать такую конфигурацию
- Частые апдейты прогресса: `COALESCE + dedupKey`.
- Визуальная стабильность ActionBar стрима: `stickinessTicks` 4–8.
- Защита от спама источника: `sourceCooldownTicks`.

---

## 5) Полный пример: Title

```kotlin
val titleRequest = TitleRequest(
    title = Component.text("Квест завершен!"),
    subtitle = Component.text("+500 опыта"),
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

hud.submitTitle(player.uniqueId, titleRequest)
```

### Рекомендации по Title
- Используйте `PREEMPT` только для реально важных событий.
- Не задавайте чрезмерный `stayTicks`, чтобы не блокировать канал.

---

## 6) Полный пример: Scoreboard

```kotlin
val sbRequest = ScoreboardRequest(
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

hud.submitScoreboard(player.uniqueId, sbRequest)
```

### Ограничения Scoreboard
- До 15 строк отображения.
- Для постоянного владельца используйте `ownerMode = true`.

---

## 7) Thread-safety и main thread

Начиная с актуальной версии сервиса, `submitActionBar/submitTitle/submitScoreboard` безопасно вызываются даже из async-контекста: оркестратор автоматически выполняет их на main thread.

Тем не менее, для явной и читаемой интеграции рекомендуется использовать thread-safe методы-обёртки:

```kotlin
hud.submitActionBarThreadSafe(plugin, player.uniqueId, request)
hud.submitTitleThreadSafe(plugin, player.uniqueId, titleRequest)
hud.submitScoreboardThreadSafe(plugin, player.uniqueId, sbRequest)
```


### Пример: async источник (БД/HTTP) с обработкой результата

```kotlin
plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
    val response = loadQuestStateFromStorage(player.uniqueId)

    val request = TitleRequest(
        title = Component.text("Новая цель"),
        subtitle = Component.text(response.targetName),
        meta = HudRequestMeta(
            sourceId = "QuestCore:async-sync",
            priority = Priority.HIGH.weight,
            policy = DeliveryPolicy.COALESCE,
            dedupKey = "quest:async:${response.questId}",
            ttlTicks = 60,
            minShowTicks = 20,
            maxShowTicks = 60
        )
    )

    hud.submitTitleThreadSafe(plugin, player.uniqueId, request)
        .thenAccept { handle ->
            if (handle == null) {
                plugin.logger.fine("HudOrchestrator backpressure for ${player.uniqueId}")
            }
        }
})
```

### Практика
Если у вас данные приходят из async (БД, HTTP, Redis), формируйте request в async, а отправку делайте только через `submit*ThreadSafe`.

---

## 8) Политики доставки (DeliveryPolicy)

### `ENQUEUE`
Стандартная очередь.

Используйте, когда важно показать все сообщения по порядку (но не слишком часто).

### `PREEMPT`
Вытесняет текущее сообщение при достаточном приоритете.

Используйте только для критичных событий (fail/error/important alert).

### `COALESCE`
Схлопывает одинаковые/родственные апдейты по `dedupKey`/`replaceGroup`.

Лучший выбор для таймеров, прогресса, кулдаунов.

### `DROP_IF_BUSY`
Отбрасывает сообщение, если канал занят.

Подходит для второстепенных уведомлений, которые не должны мешать важным.

---

## 9) Приоритеты и anti-starvation

- Приоритет задаётся числом (`Int`) через `meta.priority`.
- Сервис использует aging-механику: долгоживущие элементы постепенно повышают эффективный приоритет.

Рекомендуемый диапазон:
- `LOW`: 20–35
- `NORMAL`: 45–60
- `HIGH`: 65–80
- `CRITICAL`: 85+

---

## 10) Rate-limit и backpressure

На каждого игрока и `sourceId` применяется лимит:
- token bucket (`capacity`, `refill-per-second`)
- optional cooldown (`sourceCooldownTicks`)

Если лимит/политика не позволяют принять сообщение — `submit*` вернёт `null`.

### Как реагировать на `null`
1. Уменьшить частоту генерации.
2. Перейти на `COALESCE`.
3. Увеличить `sourceCooldownTicks`.
4. Проверить лимиты в `config.yml`.

---

## 11) Стандарты `sourceId` (очень важно)

Используйте стабильные идентификаторы:
- `PluginName`
- `PluginName:subsystem`

Примеры:
- `NoUseItem:restrictions`
- `ArtifactItems:totem-cooldown`
- `QuestCore:progress`

Почему это важно:
- корректная очистка при disable/reload плагина,
- понятная диагностика,
- предсказуемое применение rate-limit.

---

## 12) Cleanup на disable/reload

При `PluginDisableEvent` оркестратор очищает queued/active элементы плагина, если `sourceId`:
- равен имени плагина,
- или начинается с `PluginName:`.

Для scoreboard также выполняется попытка восстановить предыдущий scoreboard игрока.

---

## 13) Метрики и наблюдаемость

```kotlin
val snapshot = hud.metricsSnapshot()
logger.info("submitted=${snapshot.submitted}, rejected=${snapshot.rejectedByRateLimit}")
```

Доступные счётчики:
- `submitted`
- `rejectedByRateLimit`
- `droppedByPolicy`
- `replacedByCoalesce`
- `queueOverflowDropped`
- `preemptions`

### Базовые production SLO
- `rejectedByRateLimit / submitted < 5%` (среднее окно).
- `queueOverflowDropped == 0` на нормальной нагрузке.
- `preemptions` не должны линейно расти с онлайном.

---

## 14) Практические профили интеграции

### A) Частый прогресс (квесты)
- `COALESCE`
- `priority` 50–60
- `sourceCooldownTicks` 1–3
- `stickinessTicks` 4–8

### B) Ошибка/запрет действия
- `DROP_IF_BUSY` или `PREEMPT` (только если действительно критично)
- `priority` 30 для мягких, 80+ для критичных

### C) Смена режима (артефакты)
- `PREEMPT`
- `priority` 70+
- короткий TTL (20–40)

---

## 15) Диагностика проблем

Если сообщения не видны / мигают / конфликтуют:

1. Включите `debug.queue-logging: true`.
2. Проверьте, что стримы используют `COALESCE`.
3. Проверьте приоритеты (нет ли агрессивного `PREEMPT`).
4. Проверьте лимиты `rate-limit.*`.
5. Для ActionBar потока включите `stickinessTicks`.
6. После диагностики выключите debug-лог в проде.

---

## 16) Команда управления

Перезагрузка конфигурации и сервиса:

```text
/hudorchestrator reload
```

Permission:

```text
hudorchestrator.admin
```

---

## 17) Integration checklist

Перед выкладкой:

1. Проверить получение API через `ServicesManager`.
2. Зафиксировать `sourceId` для каждого сценария.
3. Для частых апдейтов использовать `COALESCE + dedupKey`.
4. Для вторичных сообщений использовать `DROP_IF_BUSY`.
5. Из async использовать только `submit*ThreadSafe`.
6. Обрабатывать `null` как штатный backpressure.
7. Проверить метрики под пиковым онлайном.

---

## 18) Связанные документы

- Матрица готовых профилей: [`docs/PLUGIN_POLICY_MATRIX.md`](./PLUGIN_POLICY_MATRIX.md)
