# HudOrchestrator API

## Назначение
`HudOrchestrator` предоставляет единый сервис для отправки HUD-контента игрокам:
- `ACTION_BAR`
- `TITLE`
- `SCOREBOARD`

Сервис доступен через Bukkit `ServicesManager` по интерфейсу:
`ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi`.

---

## Быстрая интеграция

```kotlin
val registration = server.servicesManager.getRegistration(HudOrchestratorApi::class.java)
val hud = registration?.provider ?: return

val handle = hud.submitActionBar(
    player.uniqueId,
    ActionBarRequest(
        content = Component.text("Квест обновлен"),
        meta = HudRequestMeta(
            sourceId = "QuestCore:progress",
            priority = Priority.HIGH.weight,
            policy = DeliveryPolicy.COALESCE,
            dedupKey = "quest_progress",
            ttlTicks = 40,
            minShowTicks = 10,
            maxShowTicks = 40,
            sourceCooldownTicks = 2
        ),
        resendIntervalTicks = 10
    )
)
```

Если вызов идет из async-кода, используйте thread-safe helper:

```kotlin
hud.submitActionBarThreadSafe(plugin, player.uniqueId, request)
```

---

## Модель приоритета и очереди

- Очередь ведется **отдельно по каждому игроку и каналу**.
- Поддерживаются политики:
  - `ENQUEUE` — обычная очередь.
  - `PREEMPT` — вытеснение текущего сообщения при достаточном приоритете.
  - `COALESCE` — схлопывание по `dedupKey`/`replaceGroup`.
  - `DROP_IF_BUSY` — удаление запроса, если канал занят.
- Для предотвращения starvation используется aging (эффективный приоритет растет со временем ожидания).

---

## Anti-spam / Rate limiting

На каждого игрока и источник (`sourceId`) применяется ограничение:
- token bucket (ёмкость + пополнение),
- optional cooldown (`sourceCooldownTicks`).

Если лимит превышен, `submit*` вернет `null`.

Лимиты настраиваются в `config.yml` по каналам:
- `rate-limit.action-bar`
- `rate-limit.title`
- `rate-limit.scoreboard`

---

## Рекомендации по `sourceId`

Используйте стабильные значения в формате:
- `PluginName`
- `PluginName:subsystem`

Примеры:
- `QuestCore:progress`
- `ArtifactItems:totem-cooldown`
- `NoUseItem`

Это важно для корректной очистки при перезагрузке/выключении плагинов.

---

## Поведение при reload / disable внешних плагинов

HudOrchestrator подписан на `PluginDisableEvent` и автоматически очищает queued/active элементы для отключаемого плагина, если его `sourceId`:
- равен имени плагина, или
- начинается с `PluginName:`.

Это предотвращает зависшие HUD-сообщения и «мертвого владельца» scoreboard после `/reload`, PlugMan-like reload или ручного disable/enable.

---

## Ограничения

- Вызовы API должны выполняться на main thread сервера.
- Scoreboard рендер ограничен 15 строками.
- При переполнении очереди слабоприоритетные элементы могут быть вытеснены.

---

## Метрики

Доступен срез метрик через API:

```kotlin
val snapshot = hud.metricsSnapshot()
```

Поля snapshot:
- submitted
- rejectedByRateLimit
- droppedByPolicy
- replacedByCoalesce
- queueOverflowDropped
- preemptions
