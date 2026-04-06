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

---

## Production playbook (SLO-тюнинг)

### 1) Async-интеграция (обязательно)

- Если событие/обработчик работает не на main thread, вызывайте только:
  - `submitActionBarThreadSafe(...)`
  - `submitTitleThreadSafe(...)`
  - `submitScoreboardThreadSafe(...)`
- Прямые `submit*` используйте только на main thread.

### 2) Базовые SLO для HUD

Рекомендуемые ориентиры для большого онлайна:
- `rejectedByRateLimit / submitted < 5%` в среднем.
- `queueOverflowDropped == 0` на нормальной нагрузке.
- `droppedByPolicy` допустим только для намеренных `DROP_IF_BUSY` сценариев.
- `preemptions` не должен расти линейно с онлайном (признак конфликтов приоритетов).

### 3) Тюнинг rate-limit по каналам

Тюнинг выполняется через `config.yml`:
- `rate-limit.action-bar` — обычно самый “шумный” канал.
- `rate-limit.title` — ограничивайте строже, чтобы не мигал экран.
- `rate-limit.scoreboard` — обновления реже, но стабильнее.

Правило:
- если растет `rejectedByRateLimit` и теряются важные сообщения → увеличивайте `capacity`/`refill-per-second`;
- если растет визуальный спам → уменьшайте `refill-per-second` и/или повышайте `sourceCooldownTicks` в запросах.

### 4) Приоритеты и политики

- Критичные сообщения (`CRITICAL`/`HIGH`) отправляйте с `PREEMPT` только при реальной необходимости.
- Частые прогресс-обновления используйте через `COALESCE` + `dedupKey`.
- Декоративные/маловажные события — `DROP_IF_BUSY`.

### 5) Рекомендуемый operational цикл

1. Снять baseline метрик на обычном онлайне.
2. Провести пиковый сценарий (ивент/вайп/массовая активность).
3. Проверить долю reject/drop/preempt.
4. Подкрутить лимиты в `config.yml`.
5. Повторить тест до стабильного профиля.
