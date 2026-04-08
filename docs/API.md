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

При очистке состояния HudOrchestrator также пытается восстановить предыдущий scoreboard игрока, если текущий был установлен оркестратором.

---

## Ограничения

- Вызовы API должны выполняться на main thread сервера.
- Scoreboard рендер ограничен 15 строками.
- При переполнении очереди слабоприоритетные элементы могут быть вытеснены.
- Для диагностики очередей доступен debug-флаг `debug.queue-logging` в `config.yml`.
- Повторяющиеся REJECT-логи агрегируются и выводятся с суффиксом `xN` (например, `x4`) для быстрого выявления спама.
- Для борьбы с «миганием» actionbar при частых апдейтах одного источника используйте `stickinessTicks` в `HudRequestMeta`.

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
- Если `submit*` вернул `null`, запрос был отклонён rate-limit/переполнением очереди — это не ошибка API, а сигнал backpressure.

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

---

## Готовая матрица для ваших плагинов

Для `NoUseItem`, `ArtifactItems`, `QuestCore` подготовлен отдельный production-профиль:

- [`docs/PLUGIN_POLICY_MATRIX.md`](./PLUGIN_POLICY_MATRIX.md)

---

## Reload команды

Для перезагрузки конфига и сервиса без рестарта сервера:

`/hudorchestrator reload`

Требуемое право:

`hudorchestrator.admin`

---

## Практический integration checklist (рекомендуется)

Перед запуском на прод:

1. Получай `HudOrchestratorApi` через `ServicesManager` и проверяй `null`-случай.
2. Используй стабильный `sourceId` в формате `PluginName:subsystem`.
3. Для частых обновлений (таймеры/кулдауны/прогресс) всегда ставь:
   - `policy = COALESCE`
   - `dedupKey`
4. Для второстепенных уведомлений используй `DROP_IF_BUSY`.
5. Для критичных событий (ошибка/фейл/блок) используй `PREEMPT`, но дозированно.
6. Из async-кода отправляй только через `submit*ThreadSafe`.
7. Обрабатывай `submit* == null` как backpressure (уменьшай частоту, повышай cooldown, coalesce).
8. Для ActionBar-стримов одного источника используй `stickinessTicks` (обычно 4–8).

---

## Рекомендуемые профили по каналам

### ActionBar
- Частота: высокая.
- Типичные policy: `COALESCE`, реже `DROP_IF_BUSY`.
- `sourceCooldownTicks`: 1–4 для таймеров, 6–12 для предупреждений.
- `stickinessTicks`: 4–8, если есть риск однокадрового “вклинивания”.

### Title
- Частота: низкая.
- Типичные policy: `PREEMPT` только для действительно важных событий.
- Не злоупотребляй длинными `stayTicks`, чтобы не блокировать полезные title.

### Scoreboard
- Частота: низкая/средняя.
- Предпочтительно обновлять только при изменении данных.
- Для постоянного владельца использовать `ownerMode=true`.

---

## Анти-паттерны (чего избегать)

- Отправка одного и того же HUD-сообщения каждый тик без `COALESCE`.
- `PREEMPT` для всех сообщений подряд (ломает UX других плагинов).
- Случайные `sourceId` (UUID/временные строки), которые ломают cleanup и диагностику.
- Игнорирование `null` от `submit*`.
- Вызовы прямых `submit*` из async-потоков.

---

## Диагностика проблем в бою

Если HUD “пропадает” или “мигает”:

1. Включи `debug.queue-logging: true`.
2. Проверь типы сообщений:
   - много `REJECT` => слишком агрессивная частота/малые лимиты;
   - много `DROP` => переполнение/неправильный приоритет;
   - много `PREEMPT` => слишком конфликтная матрица важности.
3. Проверь, что интегратор использует правильные policy (`COALESCE` для стримов).
4. Для ActionBar включи `stickinessTicks`.
5. После настройки выключи debug-логирование на проде.

---

## Минимальный контракт для интеграторов (рекомендация)

Для каждого HUD-сценария зафиксируй:
- `sourceId`
- `policy`
- `priority`
- `ttlTicks`
- `sourceCooldownTicks`
- `dedupKey` (если `COALESCE`)
- `stickinessTicks` (для ActionBar-стримов)

Это сильно снижает шанс конфликтов при росте онлайна и упрощает поддержку.
