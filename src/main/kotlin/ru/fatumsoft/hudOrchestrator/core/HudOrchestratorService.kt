package ru.fatumsoft.hudOrchestrator.core

import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import net.kyori.adventure.text.Component
import ru.fatumsoft.hudOrchestrator.api.ActionBarRequest
import ru.fatumsoft.hudOrchestrator.api.DeliveryPolicy
import ru.fatumsoft.hudOrchestrator.api.HudChannel
import ru.fatumsoft.hudOrchestrator.api.HudHandle
import ru.fatumsoft.hudOrchestrator.api.HudMetricsSnapshot
import ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi
import ru.fatumsoft.hudOrchestrator.api.ScoreboardRequest
import ru.fatumsoft.hudOrchestrator.api.TitleRequest
import ru.fatumsoft.hudOrchestrator.scheduler.FoliaHudScheduler
import ru.fatumsoft.hudOrchestrator.scheduler.ScheduledHudTask
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.max

data class ChannelRateLimitConfig(
    val capacity: Double,
    val refillPerSecond: Double
)

data class SourcePolicyOverride(
    val pattern: String,
    val priority: Int? = null,
    val policy: DeliveryPolicy? = null,
    val sourceCooldownTicks: Int? = null,
    val stickinessTicks: Int? = null,
    val dominanceTicks: Int? = null
)

data class HudOrchestratorRuntimeConfig(
    val actionBarRateLimit: ChannelRateLimitConfig,
    val titleRateLimit: ChannelRateLimitConfig,
    val scoreboardRateLimit: ChannelRateLimitConfig,
    val queueLoggingEnabled: Boolean,
    val sourcePolicyOverrides: List<SourcePolicyOverride> = emptyList(),
    val actionBarFallbackIdleGraceTicks: Int = 6
) {
    fun forChannel(channel: HudChannel): ChannelRateLimitConfig {
        return when (channel) {
            HudChannel.ACTION_BAR -> actionBarRateLimit
            HudChannel.TITLE -> titleRateLimit
            HudChannel.SCOREBOARD -> scoreboardRateLimit
        }
    }
}

class HudOrchestratorService(
    private val plugin: JavaPlugin,
    private val runtimeConfig: HudOrchestratorRuntimeConfig
) : HudOrchestratorApi, Listener {

    private val states = ConcurrentHashMap<UUID, PlayerHudState>()
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val sequence = AtomicLong(0L)
    private val scheduler = FoliaHudScheduler(plugin)
    private var task: ScheduledHudTask? = null
    private val warnedInvalidSources = ConcurrentHashMap.newKeySet<String>()
    private val metrics = HudMetrics()
    private val queueLogLastTick = ConcurrentHashMap<String, Long>()
    private val queueLogRepeatCount = ConcurrentHashMap<String, Int>()

    private fun queueLog(message: String) {
        if (!runtimeConfig.queueLoggingEnabled) return
        queueLogThrottled(message, 20L, message)
    }

    private fun queueLogThrottled(key: String, minIntervalTicks: Long, message: String) {
        if (!runtimeConfig.queueLoggingEnabled) return
        val now = currentTick()
        val newCount = (queueLogRepeatCount[key] ?: 0) + 1
        queueLogRepeatCount[key] = newCount
        val last = queueLogLastTick[key]
        if (last != null && (now - last) < minIntervalTicks) return

        val suffix = if (newCount > 1) " x$newCount" else ""
        queueLogLastTick[key] = now
        queueLogRepeatCount[key] = 0
        plugin.logger.info("[HudQueue] $message$suffix")
    }

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        task = scheduler.runGlobalRepeating(1L, 1L) { tick() }
    }

    fun shutdown() {
        task?.cancel()
        task = null
        states.forEach { (playerId, state) ->
            clearVisualStateOnPlayerThread(playerId, state)
        }
        states.clear()
        activePlayers.clear()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        val disabledName = event.plugin.name
        if (disabledName.equals(plugin.name, ignoreCase = true)) return

        states.values.forEach { it.cancelByPluginName(disabledName) }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerKick(event: PlayerKickEvent) {
        clearPlayer(event.player.uniqueId)
    }

    private fun <T> runOnPlayerThread(playerId: UUID, actionName: String, block: () -> T): T? {
        val player = Bukkit.getPlayer(playerId) ?: return null
        if (!player.isOnline) return null
        return try {
            scheduler.supplyPlayer(player, block).get()
        } catch (ex: Exception) {
            throw IllegalStateException("$actionName must run on player entity thread", ex)
        }
    }


    private fun applyOverrides(meta: ru.fatumsoft.hudOrchestrator.api.HudRequestMeta): ru.fatumsoft.hudOrchestrator.api.HudRequestMeta {
        val overrides = runtimeConfig.sourcePolicyOverrides
        if (overrides.isEmpty()) return meta
        val matched = overrides.firstOrNull { sourceMatches(it.pattern, meta.sourceId) } ?: return meta
        return meta.copy(
            priority = matched.priority ?: meta.priority,
            policy = matched.policy ?: meta.policy,
            sourceCooldownTicks = matched.sourceCooldownTicks ?: meta.sourceCooldownTicks,
            stickinessTicks = matched.stickinessTicks ?: meta.stickinessTicks,
            dominanceTicks = matched.dominanceTicks ?: meta.dominanceTicks
        )
    }

    private fun sourceMatches(pattern: String, sourceId: String): Boolean {
        if (pattern == "*") return true
        val regex = pattern
            .split('*')
            .joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(sourceId)
    }

    override fun submitActionBar(playerId: UUID, request: ActionBarRequest): HudHandle? = runOnPlayerThread(playerId, "submitActionBar") {
        val effectiveMeta = applyOverrides(request.meta)
        val effectiveRequest = request.copy(meta = effectiveMeta)
        validateSourceId(effectiveMeta.sourceId)
        val nowTick = currentTick()
        val entry = QueueEntry.ActionBar(
            request = effectiveRequest,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.ACTION_BAR, effectiveRequest.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(effectiveRequest.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        val state = playerState(playerId)
        if (effectiveRequest.meta.policy == DeliveryPolicy.DROP_IF_BUSY && state.isActionBarBlockedByHigherPriority(effectiveRequest.meta.priority, nowTick)) {
            metrics.droppedByPolicy.increment()
            queueLog("DROP channel=ACTION_BAR player=$playerId source=${effectiveRequest.meta.sourceId} reason=higher_priority_active_or_pending priority=${effectiveRequest.meta.priority}")
            return@runOnPlayerThread null
        }
        val bypassByCoalesce = state.actionBarQueue.hasPendingCoalesceTarget(entry)
        val bypassForIdleDropIfBusy = effectiveRequest.meta.policy == DeliveryPolicy.DROP_IF_BUSY && state.isActionBarChannelFree(nowTick)
        val bypassRateLimit = bypassByCoalesce || bypassForIdleDropIfBusy
        val effectiveCooldownTicks = if (effectiveRequest.meta.policy == DeliveryPolicy.DROP_IF_BUSY) 0 else effectiveRequest.meta.sourceCooldownTicks
        if (!bypassRateLimit && !isAccepted(playerId, HudChannel.ACTION_BAR, effectiveRequest.meta.sourceId, effectiveCooldownTicks)) {
            val debug = state.actionBarDebugState(nowTick)
            queueLog("REJECT_DETAIL channel=ACTION_BAR player=$playerId source=${effectiveRequest.meta.sourceId} policy=${effectiveRequest.meta.policy} channelFree=${debug.channelFree} activeSource=${debug.activeSource ?: "none"} queueSize=${debug.queueSize}")
            return@runOnPlayerThread null
        }
        val result = state.actionBarQueue.offer(entry)
        activePlayers.add(playerId)
        if (result == OfferResult.DROPPED_BY_OVERFLOW) {
            metrics.queueOverflowDropped.increment()
            queueLog("DROP channel=ACTION_BAR player=$playerId source=${effectiveRequest.meta.sourceId} reason=overflow priority=${effectiveRequest.meta.priority}")
            return@runOnPlayerThread null
        }
        metrics.submitted.increment()
        if (result == OfferResult.REPLACED_BY_COALESCE) metrics.replacedByCoalesce.increment()
        state.rememberActionBarPriorityFloor(effectiveRequest.meta, nowTick)
        state.rememberActionBarDominance(effectiveRequest.meta, nowTick)
        val removedFallbacks = state.dropLowerPriorityActionBarFallbacks(effectiveRequest.meta.priority)
        if (removedFallbacks > 0) {
            repeat(removedFallbacks) { metrics.droppedByPolicy.increment() }
            queueLog("DROP channel=ACTION_BAR player=$playerId source=${effectiveRequest.meta.sourceId} reason=lower_priority_fallback_superseded removed=$removedFallbacks priority=${effectiveRequest.meta.priority}")
        }
        queueLog("ENQUEUE channel=ACTION_BAR player=$playerId source=${effectiveRequest.meta.sourceId} policy=${effectiveRequest.meta.policy} priority=${effectiveRequest.meta.priority} result=$result")
        if (effectiveRequest.meta.policy != DeliveryPolicy.DROP_IF_BUSY) {
            processPlayerNowIfPossible(playerId)
        }
        return@runOnPlayerThread entry.handle
    }

    override fun submitTitle(playerId: UUID, request: TitleRequest): HudHandle? = runOnPlayerThread(playerId, "submitTitle") {
        val effectiveMeta = applyOverrides(request.meta)
        val effectiveRequest = request.copy(meta = effectiveMeta)
        validateSourceId(effectiveMeta.sourceId)
        val nowTick = currentTick()
        val entry = QueueEntry.Title(
            request = effectiveRequest,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.TITLE, effectiveRequest.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(effectiveRequest.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        val state = playerState(playerId)
        val bypassRateLimit = state.titleQueue.hasPendingCoalesceTarget(entry)
        if (!bypassRateLimit && !isAccepted(playerId, HudChannel.TITLE, effectiveRequest.meta.sourceId, effectiveRequest.meta.sourceCooldownTicks)) return@runOnPlayerThread null
        val result = state.titleQueue.offer(entry)
        activePlayers.add(playerId)
        if (result == OfferResult.DROPPED_BY_OVERFLOW) {
            metrics.queueOverflowDropped.increment()
            queueLog("DROP channel=TITLE player=$playerId source=${effectiveRequest.meta.sourceId} reason=overflow priority=${effectiveRequest.meta.priority}")
            return@runOnPlayerThread null
        }
        metrics.submitted.increment()
        if (result == OfferResult.REPLACED_BY_COALESCE) metrics.replacedByCoalesce.increment()
        queueLog("ENQUEUE channel=TITLE player=$playerId source=${effectiveRequest.meta.sourceId} policy=${effectiveRequest.meta.policy} priority=${effectiveRequest.meta.priority} result=$result")
        processPlayerNowIfPossible(playerId)
        return@runOnPlayerThread entry.handle
    }

    override fun submitScoreboard(playerId: UUID, request: ScoreboardRequest): HudHandle? = runOnPlayerThread(playerId, "submitScoreboard") {
        val effectiveMeta = applyOverrides(request.meta)
        val effectiveRequest = request.copy(meta = effectiveMeta)
        validateSourceId(effectiveMeta.sourceId)
        val nowTick = currentTick()
        val entry = QueueEntry.Scoreboard(
            request = effectiveRequest,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.SCOREBOARD, effectiveRequest.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(effectiveRequest.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        val state = playerState(playerId)
        val bypassRateLimit = state.scoreboardQueue.hasPendingCoalesceTarget(entry)
        if (!bypassRateLimit && !isAccepted(playerId, HudChannel.SCOREBOARD, effectiveRequest.meta.sourceId, effectiveRequest.meta.sourceCooldownTicks)) return@runOnPlayerThread null
        val result = state.scoreboardQueue.offer(entry)
        activePlayers.add(playerId)
        if (result == OfferResult.DROPPED_BY_OVERFLOW) {
            metrics.queueOverflowDropped.increment()
            queueLog("DROP channel=SCOREBOARD player=$playerId source=${effectiveRequest.meta.sourceId} reason=overflow priority=${effectiveRequest.meta.priority}")
            return@runOnPlayerThread null
        }
        metrics.submitted.increment()
        if (result == OfferResult.REPLACED_BY_COALESCE) metrics.replacedByCoalesce.increment()
        queueLog("ENQUEUE channel=SCOREBOARD player=$playerId source=${effectiveRequest.meta.sourceId} policy=${effectiveRequest.meta.policy} priority=${effectiveRequest.meta.priority} result=$result")
        processPlayerNowIfPossible(playerId)
        return@runOnPlayerThread entry.handle
    }

    override fun cancel(handle: HudHandle): Boolean {
        return states[handle.playerId]?.cancel(handle) ?: false
    }

    override fun cancelBySource(sourceId: String, playerId: UUID?, channel: HudChannel?): Int {
        return if (playerId != null) {
            states[playerId]?.cancelBySource(sourceId, channel) ?: 0
        } else {
            states.values.sumOf { it.cancelBySource(sourceId, channel) }
        }
    }

    override fun clearPlayer(playerId: UUID) {
        val state = states.remove(playerId)
        activePlayers.remove(playerId)
        if (state != null) clearVisualStateOnPlayerThread(playerId, state)
    }

    override fun metricsSnapshot(): HudMetricsSnapshot = metrics.snapshot()


    private fun clearVisualStateOnPlayerThread(playerId: UUID, state: PlayerHudState) {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline) {
            state.clearVisualState(null)
            return
        }
        scheduler.runPlayer(player, {
            state.clearVisualState(player)
        }, retired = {
            state.clearVisualState(null)
        })
    }

    private fun tick() {
        val nowTick = currentTick()
        if (activePlayers.isEmpty()) return

        val iterator = activePlayers.iterator()
        while (iterator.hasNext()) {
            val playerId = iterator.next()
            val state = states[playerId]
            if (state == null) {
                iterator.remove()
                continue
            }

            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                state.clearVisualState(null)
                states.remove(playerId)
                iterator.remove()
                continue
            }

            scheduler.runPlayer(player, {
                try {
                    state.process(player, nowTick)
                } catch (t: Throwable) {
                    plugin.logger.severe("HudOrchestrator tick failed for player=$playerId: ${t.message}")
                    t.printStackTrace()
                }
                if (state.isIdle()) {
                    state.clearVisualState(player)
                    states.remove(playerId)
                    activePlayers.remove(playerId)
                }
            }, retired = {
                state.clearVisualState(null)
                states.remove(playerId)
                activePlayers.remove(playerId)
            })
        }
    }

    private fun playerState(playerId: UUID): PlayerHudState {
        return states.computeIfAbsent(playerId) { PlayerHudState(runtimeConfig, metrics, ::queueLog) }
    }

    private fun processPlayerNowIfPossible(playerId: UUID) {
        val player = Bukkit.getPlayer(playerId) ?: return
        if (!player.isOnline) return
        val state = states[playerId] ?: return
        scheduler.runPlayer(player, {
            try {
                state.process(player, currentTick())
            } catch (t: Throwable) {
                plugin.logger.severe("HudOrchestrator immediate process failed for player=$playerId: ${t.message}")
                t.printStackTrace()
            }
        }, retired = {
            states.remove(playerId)
            activePlayers.remove(playerId)
        })
    }

    private fun isAccepted(playerId: UUID, channel: HudChannel, sourceId: String, cooldownTicks: Int): Boolean {
        val state = playerState(playerId)
        val accepted = state.rateLimiter.accept(channel, sourceId, currentTick(), cooldownTicks)
        if (!accepted) {
            metrics.rejectedByRateLimit.increment()
            val throttleKey = "REJECT:$channel:$playerId:$sourceId:$cooldownTicks"
            val details = if (channel == HudChannel.ACTION_BAR) {
                val debug = state.actionBarDebugState(currentTick())
                " channelFree=${debug.channelFree} activeSource=${debug.activeSource ?: "none"} queueSize=${debug.queueSize}"
            } else ""
            queueLogThrottled(
                key = throttleKey,
                minIntervalTicks = 40L,
                message = "REJECT channel=$channel player=$playerId source=$sourceId reason=rate_limit cooldownTicks=$cooldownTicks$details"
            )
        }
        return accepted
    }

    private fun currentTick(): Long = Bukkit.getCurrentTick().toLong()

    private fun validateSourceId(sourceId: String) {
        if (SOURCE_PATTERN.matches(sourceId)) return
        if (warnedInvalidSources.add(sourceId)) {
            plugin.logger.warning("HudOrchestrator: sourceId '$sourceId' has non-recommended format. Use PluginName[:subsystem].")
        }
    }

    companion object {
        private val SOURCE_PATTERN = Regex("^[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*$")
    }
}

private class PlayerHudState(
    runtimeConfig: HudOrchestratorRuntimeConfig,
    private val metrics: HudMetrics,
    private val queueLog: (String) -> Unit
) {
    val actionBarQueue = HudQueue<QueueEntry.ActionBar>(maxSize = 64)
    val titleQueue = HudQueue<QueueEntry.Title>(maxSize = 32)
    val scoreboardQueue = HudQueue<QueueEntry.Scoreboard>(maxSize = 32)
    val rateLimiter = PlayerRateLimiter(runtimeConfig)
    private val actionBarFallbackIdleGraceTicks = runtimeConfig.actionBarFallbackIdleGraceTicks.toLong()

    private var activeActionBar: ActiveEntry<QueueEntry.ActionBar>? = null
    private var activeTitle: ActiveEntry<QueueEntry.Title>? = null
    private var activeScoreboard: ActiveEntry<QueueEntry.Scoreboard>? = null
    private var actionBarStickySource: String? = null
    private var actionBarStickyUntilTick: Long = 0L
    private var actionBarStickyPriority: Int = Int.MIN_VALUE
    private var actionBarDominanceUntilTick: Long = 0L
    private var actionBarDominancePriority: Int = Int.MIN_VALUE
    private var actionBarIdleSinceTick: Long? = null
    private var scoreboardOwner: String? = null
    private var scoreboardView: ScoreboardViewState? = null
    private var previousScoreboard: org.bukkit.scoreboard.Scoreboard? = null

    companion object {
        private const val DEFAULT_PRIORITY_FLOOR_GUARD_TICKS = 20
    }

    fun process(player: Player, nowTick: Long) {
        processActionBar(player, nowTick)
        processTitle(player, nowTick)
        processScoreboard(player, nowTick)
    }

    fun isIdle(): Boolean {
        return activeActionBar == null &&
            activeTitle == null &&
            activeScoreboard == null &&
            actionBarQueue.isEmpty() &&
            titleQueue.isEmpty() &&
            scoreboardQueue.isEmpty()
    }

    fun isActionBarChannelFree(nowTick: Long): Boolean {
        val active = activeActionBar
        return (active == null || active.isExpired(nowTick)) && actionBarQueue.isEmpty()
    }

    fun isActionBarBlockedByHigherPriority(priority: Int, nowTick: Long): Boolean {
        val active = activeActionBar
        if (active != null && !active.isExpired(nowTick) && active.entry.priority() > priority) return true
        if (nowTick < actionBarStickyUntilTick && actionBarStickyPriority > priority) return true
        if (nowTick < actionBarDominanceUntilTick && actionBarDominancePriority > priority) return true
        return actionBarQueue.hasPriorityAbove(priority)
    }

    fun canDispatchActionBarFallback(entry: QueueEntry.ActionBar, nowTick: Long): Boolean {
        if (entry.request.meta.policy != DeliveryPolicy.DROP_IF_BUSY) return true
        if (isActionBarBlockedByHigherPriority(entry.priority(), nowTick)) return false
        val active = activeActionBar
        if (active != null && !active.isExpired(nowTick)) return false
        val idleSince = actionBarIdleSinceTick ?: return false
        return nowTick - idleSince >= actionBarFallbackIdleGraceTicks && nowTick - entry.createdTick >= actionBarFallbackIdleGraceTicks
    }

    fun rememberActionBarDominance(meta: ru.fatumsoft.hudOrchestrator.api.HudRequestMeta, nowTick: Long) {
        if (meta.dominanceTicks <= 0) return
        val untilTick = nowTick + max(meta.maxShowTicks, 1) + meta.dominanceTicks
        if (nowTick >= actionBarDominanceUntilTick) {
            actionBarDominancePriority = meta.priority
            actionBarDominanceUntilTick = untilTick
            return
        }
        actionBarDominanceUntilTick = max(actionBarDominanceUntilTick, untilTick)
        actionBarDominancePriority = max(actionBarDominancePriority, meta.priority)
    }

    fun rememberActionBarPriorityFloor(meta: ru.fatumsoft.hudOrchestrator.api.HudRequestMeta, nowTick: Long) {
        if (meta.policy == DeliveryPolicy.DROP_IF_BUSY) return
        val guardTicks = max(max(meta.dominanceTicks, meta.stickinessTicks), DEFAULT_PRIORITY_FLOOR_GUARD_TICKS)
        val untilTick = nowTick + max(meta.maxShowTicks, 1) + guardTicks
        if (nowTick >= actionBarDominanceUntilTick) {
            actionBarDominancePriority = meta.priority
            actionBarDominanceUntilTick = untilTick
            return
        }
        actionBarDominanceUntilTick = max(actionBarDominanceUntilTick, untilTick)
        actionBarDominancePriority = max(actionBarDominancePriority, meta.priority)
    }

    fun dropLowerPriorityActionBarFallbacks(priority: Int): Int {
        return actionBarQueue.removeWhere { entry ->
            entry.request.meta.policy == DeliveryPolicy.DROP_IF_BUSY && entry.priority() < priority
        }
    }

    data class ActionBarDebugState(
        val channelFree: Boolean,
        val activeSource: String?,
        val queueSize: Int
    )

    fun actionBarDebugState(nowTick: Long): ActionBarDebugState {
        val active = activeActionBar
        val activeSource = if (active != null && !active.isExpired(nowTick)) active.entry.sourceId() else null
        return ActionBarDebugState(
            channelFree = (active == null || active.isExpired(nowTick)) && actionBarQueue.isEmpty(),
            activeSource = activeSource,
            queueSize = actionBarQueue.size()
        )
    }


    fun cancel(handle: HudHandle): Boolean {
        val removed = when (handle.channel) {
            HudChannel.ACTION_BAR -> actionBarQueue.remove(handle.id)
            HudChannel.TITLE -> titleQueue.remove(handle.id)
            HudChannel.SCOREBOARD -> scoreboardQueue.remove(handle.id)
        }
        if (removed) return true

        if (activeActionBar?.entry?.handle?.id == handle.id) {
            activeActionBar = null
            return true
        }
        if (activeTitle?.entry?.handle?.id == handle.id) {
            activeTitle = null
            return true
        }
        if (activeScoreboard?.entry?.handle?.id == handle.id) {
            activeScoreboard = null
            return true
        }

        return false
    }

    fun cancelBySource(sourceId: String, channel: HudChannel?): Int {
        var removed = 0
        if (channel == null || channel == HudChannel.ACTION_BAR) removed += actionBarQueue.removeBySource(sourceId)
        if (channel == null || channel == HudChannel.TITLE) removed += titleQueue.removeBySource(sourceId)
        if (channel == null || channel == HudChannel.SCOREBOARD) removed += scoreboardQueue.removeBySource(sourceId)

        if ((channel == null || channel == HudChannel.ACTION_BAR) && activeActionBar?.entry?.sourceId() == sourceId) {
            activeActionBar = null
            removed++
        }
        if ((channel == null || channel == HudChannel.TITLE) && activeTitle?.entry?.sourceId() == sourceId) {
            activeTitle = null
            removed++
        }
        if ((channel == null || channel == HudChannel.SCOREBOARD) && activeScoreboard?.entry?.sourceId() == sourceId) {
            activeScoreboard = null
            removed++
        }

        return removed
    }

    fun cancelByPluginName(pluginName: String): Int {
        var removed = 0
        removed += actionBarQueue.removeBySourcePrefix(pluginName)
        removed += titleQueue.removeBySourcePrefix(pluginName)
        removed += scoreboardQueue.removeBySourcePrefix(pluginName)

        if (activeActionBar?.entry?.sourceBelongsToPlugin(pluginName) == true) {
            activeActionBar = null
            removed++
        }
        if (activeTitle?.entry?.sourceBelongsToPlugin(pluginName) == true) {
            activeTitle = null
            removed++
        }
        if (activeScoreboard?.entry?.sourceBelongsToPlugin(pluginName) == true) {
            activeScoreboard = null
            removed++
        }

        if (scoreboardOwner != null && sourceBelongsToPlugin(scoreboardOwner!!, pluginName)) {
            scoreboardOwner = null
        }

        return removed
    }

    fun clearVisualState(player: Player?) {
        actionBarQueue.clear()
        titleQueue.clear()
        scoreboardQueue.clear()
        activeActionBar = null
        actionBarIdleSinceTick = null
        activeTitle = null
        activeScoreboard = null
        actionBarStickySource = null
        actionBarStickyUntilTick = 0L
        actionBarStickyPriority = Int.MIN_VALUE
        actionBarDominanceUntilTick = 0L
        actionBarDominancePriority = Int.MIN_VALUE
        scoreboardOwner = null
        scoreboardView = null
        if (player != null && previousScoreboard != null && player.scoreboard != previousScoreboard) {
            player.scoreboard = previousScoreboard!!
        }
        previousScoreboard = null
    }

    private fun processActionBar(player: Player, nowTick: Long) {
        actionBarQueue.discardExpired(nowTick)
        val current = activeActionBar
        if (current != null && current.isExpired(nowTick)) activeActionBar = null
        if (activeActionBar == null && actionBarIdleSinceTick == null) actionBarIdleSinceTick = nowTick

        val selected = selectActionBarCandidate(nowTick)
        if (selected != null && shouldActivate(selected, activeActionBar, nowTick)) {
            val holdUntilTick = nowTick + max(selected.request.meta.minShowTicks.toLong(), 1L)
            val expireAtTick = nowTick + max(selected.request.meta.maxShowTicks.toLong(), 1L)
            activeActionBar = ActiveEntry(selected, holdUntilTick, expireAtTick, nowTick)
            actionBarIdleSinceTick = null
            selected.send(player)
            if (selected.request.meta.stickinessTicks > 0) {
                actionBarStickySource = selected.request.meta.sourceId
                actionBarStickyUntilTick = nowTick + selected.request.meta.stickinessTicks
                actionBarStickyPriority = selected.request.meta.priority
            }
            if (selected.request.meta.dominanceTicks > 0) {
                actionBarDominanceUntilTick = expireAtTick + selected.request.meta.dominanceTicks
                actionBarDominancePriority = selected.request.meta.priority
            }
            queueLog("DISPATCH channel=ACTION_BAR player=${player.uniqueId} source=${selected.request.meta.sourceId} priority=${selected.request.meta.priority}")
        } else {
            val active = activeActionBar ?: return
            val resendEvery = max(active.entry.request.resendIntervalTicks, 1)
            if (nowTick - active.lastSendTick >= resendEvery) {
                active.entry.send(player)
                active.lastSendTick = nowTick
            }
        }
    }

    private fun selectActionBarCandidate(nowTick: Long): QueueEntry.ActionBar? {
        val stickySource = actionBarStickySource
        val stickyActive = stickySource != null && nowTick < actionBarStickyUntilTick
        if (stickyActive) {
            val stickyCandidate = actionBarQueue.bestCandidateBySource(stickySource, nowTick)
            if (stickyCandidate != null) {
                actionBarQueue.remove(stickyCandidate.handle.id)
                return stickyCandidate
            }
            // Sticky window is active but owner source has no pending update.
            // Keep currently displayed sticky owner until it expires to avoid one-tick
            // flicker/preemption by lower-priority fallback sources (e.g. NoUseItem).
            val active = activeActionBar
            if (active != null && !active.isExpired(nowTick) && active.entry.sourceId() == stickySource) {
                return null
            }

            val best = actionBarQueue.bestCandidate(nowTick)
            if (best != null && best.priority() < actionBarStickyPriority) {
                // Sticky owner stream is still considered dominant in this window.
                // Do not allow lower-priority fallback bursts to flash over it.
                return null
            }
        }
        if (nowTick < actionBarDominanceUntilTick) {
            val best = actionBarQueue.bestCandidate(nowTick)
            if (best != null && best.priority() < actionBarDominancePriority) return null
        }
        val best = actionBarQueue.bestCandidate(nowTick)
        if (best is QueueEntry.ActionBar && !canDispatchActionBarFallback(best, nowTick)) return null
        return selectNext(actionBarQueue, activeActionBar, nowTick)
    }

    private fun processTitle(player: Player, nowTick: Long) {
        titleQueue.discardExpired(nowTick)
        val current = activeTitle
        if (current != null && current.isExpired(nowTick)) activeTitle = null

        val selected = selectNext(titleQueue, activeTitle, nowTick)
        if (selected != null && shouldActivate(selected, activeTitle, nowTick)) {
            val minTicks = max(selected.request.meta.minShowTicks, selected.request.fadeInTicks + selected.request.stayTicks)
            val maxTicks = max(selected.request.meta.maxShowTicks, minTicks + selected.request.fadeOutTicks)
            activeTitle = ActiveEntry(selected, nowTick + minTicks, nowTick + maxTicks, nowTick)
            selected.send(player)
            queueLog("DISPATCH channel=TITLE player=${player.uniqueId} source=${selected.request.meta.sourceId} priority=${selected.request.meta.priority}")
        }
    }

    private fun processScoreboard(player: Player, nowTick: Long) {
        scoreboardQueue.discardExpired(nowTick)
        val current = activeScoreboard
        if (current != null && current.isExpired(nowTick)) activeScoreboard = null

        val selected = selectNext(scoreboardQueue, activeScoreboard, nowTick)
        if (selected != null && shouldActivate(selected, activeScoreboard, nowTick)) {
            if (selected.request.ownerMode) {
                scoreboardOwner = selected.request.meta.sourceId
            }

            val owner = scoreboardOwner
            if (selected.request.ownerMode || owner == null || owner == selected.request.meta.sourceId || selected.request.meta.policy == DeliveryPolicy.PREEMPT) {
                activeScoreboard = ActiveEntry(
                    selected,
                    nowTick + max(selected.request.meta.minShowTicks.toLong(), 20L),
                    nowTick + max(selected.request.meta.maxShowTicks.toLong(), 40L),
                    nowTick
                )
                if (scoreboardView == null) {
                    previousScoreboard = player.scoreboard
                }
                scoreboardView = ScoreboardRenderer.render(player, selected.request, scoreboardView)
                queueLog("DISPATCH channel=SCOREBOARD player=${player.uniqueId} source=${selected.request.meta.sourceId} priority=${selected.request.meta.priority}")
            }
        }
    }

    private fun <T : QueueEntry> shouldActivate(selected: T, current: ActiveEntry<T>?, nowTick: Long): Boolean {
        if (current == null) return true
        if (current.holdUntilTick > nowTick && selected.priority() <= current.entry.priority()) return false
        if (selected.requestMeta().policy == DeliveryPolicy.DROP_IF_BUSY && !current.isExpired(nowTick)) return false
        return selected.priority() > current.entry.priority() || current.isExpired(nowTick)
    }

    private fun <T : QueueEntry> selectNext(queue: HudQueue<T>, current: ActiveEntry<T>?, nowTick: Long): T? {
        val best = queue.bestCandidate(nowTick) ?: return null
        val policy = best.requestMeta().policy

        if (policy == DeliveryPolicy.DROP_IF_BUSY && current != null && !current.isExpired(nowTick)) {
            queue.remove(best.handle.id)
            metrics.droppedByPolicy.increment()
            return null
        }

        if (policy == DeliveryPolicy.PREEMPT) {
            queue.remove(best.handle.id)
            if (current != null && !current.isExpired(nowTick)) {
                metrics.preemptions.increment()
            }
            return best
        }

        if (current == null || current.isExpired(nowTick)) {
            queue.remove(best.handle.id)
            return best
        }

        if (best.priority() > current.entry.priority() && current.holdUntilTick <= nowTick) {
            queue.remove(best.handle.id)
            return best
        }

        return null
    }

    private data class ActiveEntry<T : QueueEntry>(
        val entry: T,
        val holdUntilTick: Long,
        val expireAtTick: Long,
        var lastSendTick: Long
    ) {
        fun isExpired(nowTick: Long): Boolean = nowTick >= expireAtTick
    }
}

private class HudQueue<T : QueueEntry>(
    private val maxSize: Int
) {
    private val queue = ArrayList<T>(maxSize)

    fun hasPendingCoalesceTarget(entry: T): Boolean {
        val meta = entry.requestMeta()
        if (meta.policy != DeliveryPolicy.COALESCE) return false
        val replaceBy = meta.dedupKey ?: meta.replaceGroup ?: return false
        return queue.any { it.sourceId() == entry.sourceId() && (it.requestMeta().dedupKey == replaceBy || it.requestMeta().replaceGroup == replaceBy) }
    }

    fun hasPriorityAbove(priority: Int): Boolean = queue.any { it.priority() > priority }

    fun offer(entry: T): OfferResult {
        val meta = entry.requestMeta()
        val replaceBy = meta.dedupKey ?: meta.replaceGroup
        if (meta.policy == DeliveryPolicy.COALESCE && replaceBy != null) {
            val idx = queue.indexOfFirst { it.sourceId() == entry.sourceId() && (it.requestMeta().dedupKey == replaceBy || it.requestMeta().replaceGroup == replaceBy) }
            if (idx >= 0) {
                queue[idx] = entry
                return OfferResult.REPLACED_BY_COALESCE
            }
        }

        if (queue.size >= maxSize) {
            val lowestIdx = queue.indices.minByOrNull { queue[it].priority() } ?: -1
            if (lowestIdx >= 0 && queue[lowestIdx].priority() < entry.priority()) {
                queue[lowestIdx] = entry
                return OfferResult.INSERTED
            }
            return OfferResult.DROPPED_BY_OVERFLOW
        }

        queue += entry
        return OfferResult.INSERTED
    }

    fun remove(handleId: UUID): Boolean {
        for (idx in queue.indices) {
            if (queue[idx].handle.id == handleId) {
                queue.removeAt(idx)
                return true
            }
        }
        return false
    }

    fun removeBySource(sourceId: String): Int {
        var removed = 0
        var idx = queue.size - 1
        while (idx >= 0) {
            if (queue[idx].sourceId() == sourceId) {
                queue.removeAt(idx)
                removed++
            }
            idx--
        }
        return removed
    }

    fun removeBySourcePrefix(pluginName: String): Int {
        var removed = 0
        var idx = queue.size - 1
        while (idx >= 0) {
            if (queue[idx].sourceBelongsToPlugin(pluginName)) {
                queue.removeAt(idx)
                removed++
            }
            idx--
        }
        return removed
    }

    fun removeWhere(predicate: (T) -> Boolean): Int {
        var removed = 0
        var idx = queue.size - 1
        while (idx >= 0) {
            if (predicate(queue[idx])) {
                queue.removeAt(idx)
                removed++
            }
            idx--
        }
        return removed
    }

    fun discardExpired(nowTick: Long) {
        var idx = queue.size - 1
        while (idx >= 0) {
            if (queue[idx].expireTick <= nowTick) {
                queue.removeAt(idx)
            }
            idx--
        }
    }

    fun bestCandidate(nowTick: Long): T? {
        return bestCandidateInternal(nowTick) { true }
    }

    fun bestCandidateBySource(sourceId: String, nowTick: Long): T? {
        return bestCandidateInternal(nowTick) { it.sourceId() == sourceId }
    }

    private fun bestCandidateInternal(nowTick: Long, predicate: (T) -> Boolean): T? {
        var best: T? = null
        var bestPriority = Int.MIN_VALUE
        var bestWaitScore = Int.MIN_VALUE
        for (entry in queue) {
            if (!predicate(entry)) continue
            val priority = entry.priority()
            val waitScore = ((nowTick - entry.createdTick).coerceAtLeast(0) / 20L).toInt()
            if (priority > bestPriority ||
                (priority == bestPriority && (waitScore > bestWaitScore || (waitScore == bestWaitScore && (best == null || entry.seq < best.seq))))
            ) {
                best = entry
                bestPriority = priority
                bestWaitScore = waitScore
            }
        }
        return best
    }

    fun clear() = queue.clear()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size
}

private enum class OfferResult {
    INSERTED,
    REPLACED_BY_COALESCE,
    DROPPED_BY_OVERFLOW
}

private class PlayerRateLimiter {
    private val stateBySourceAndChannel = HashMap<String, SourceLimiterState>(32)
    private val runtimeConfig: HudOrchestratorRuntimeConfig

    constructor(runtimeConfig: HudOrchestratorRuntimeConfig) {
        this.runtimeConfig = runtimeConfig
    }

    fun accept(channel: HudChannel, sourceId: String, nowTick: Long, cooldownTicks: Int): Boolean {
        val key = "${channel.name}:$sourceId"
        val rate = runtimeConfig.forChannel(channel)
        val state = stateBySourceAndChannel.computeIfAbsent(key) {
            SourceLimiterState(
                lastRefillTick = nowTick,
                tokens = rate.capacity,
                capacity = rate.capacity,
                refillPerSecond = rate.refillPerSecond
            )
        }

        state.refill(nowTick)

        if (cooldownTicks > 0 && nowTick - state.lastAcceptTick < cooldownTicks) {
            return false
        }

        if (state.tokens < 1.0) {
            return false
        }

        state.tokens -= 1.0
        state.lastAcceptTick = nowTick
        return true
    }

    private data class SourceLimiterState(
        var lastRefillTick: Long,
        var lastAcceptTick: Long = Long.MIN_VALUE,
        var tokens: Double = 10.0,
        val capacity: Double = 10.0,
        val refillPerSecond: Double = 5.0
    ) {
        fun refill(nowTick: Long) {
            val deltaTicks = nowTick - lastRefillTick
            if (deltaTicks <= 0) return
            val refill = deltaTicks / 20.0 * refillPerSecond
            tokens = (tokens + refill).coerceAtMost(capacity)
            lastRefillTick = nowTick
        }
    }
}

private sealed class QueueEntry(
    val handle: HudHandle,
    val createdTick: Long,
    val expireTick: Long,
    val seq: Long
) {
    abstract fun requestMeta() : ru.fatumsoft.hudOrchestrator.api.HudRequestMeta
    abstract fun send(player: Player)
    fun priority(): Int = requestMeta().priority
    fun sourceId(): String = requestMeta().sourceId
    fun sourceBelongsToPlugin(pluginName: String): Boolean = sourceBelongsToPlugin(sourceId(), pluginName)

    class ActionBar(
        val request: ActionBarRequest,
        handle: HudHandle,
        createdTick: Long,
        expireTick: Long,
        seq: Long
    ) : QueueEntry(handle, createdTick, expireTick, seq) {
        override fun requestMeta() = request.meta
        override fun send(player: Player) {
            player.sendActionBar(request.content)
        }
    }

    class Title(
        val request: TitleRequest,
        handle: HudHandle,
        createdTick: Long,
        expireTick: Long,
        seq: Long
    ) : QueueEntry(handle, createdTick, expireTick, seq) {
        override fun requestMeta() = request.meta
        override fun send(player: Player) {
            player.showTitle(
                net.kyori.adventure.title.Title.title(
                    request.title,
                    request.subtitle,
                    net.kyori.adventure.title.Title.Times.times(
                        Duration.ofMillis((request.fadeInTicks * 50L).coerceAtLeast(0L)),
                        Duration.ofMillis((request.stayTicks * 50L).coerceAtLeast(0L)),
                        Duration.ofMillis((request.fadeOutTicks * 50L).coerceAtLeast(0L))
                    )
                )
            )
        }
    }

    class Scoreboard(
        val request: ScoreboardRequest,
        handle: HudHandle,
        createdTick: Long,
        expireTick: Long,
        seq: Long
    ) : QueueEntry(handle, createdTick, expireTick, seq) {
        override fun requestMeta() = request.meta

        override fun send(player: Player) {
            ScoreboardRenderer.render(player, request, previous = null)
        }
    }
}

private fun sourceBelongsToPlugin(sourceId: String, pluginName: String): Boolean {
    return sourceId.equals(pluginName, ignoreCase = true) ||
        sourceId.startsWith("$pluginName:", ignoreCase = true)
}

private data class ScoreboardViewState(
    val board: org.bukkit.scoreboard.Scoreboard,
    val objective: Objective,
    var sidebar: Boolean,
    var title: Component,
    val linesByIndex: MutableMap<Int, Component>
)

private object ScoreboardRenderer {
    private const val OBJECTIVE_NAME = "hud_orchestrator"
    private val ENTRIES = Array(15) { i -> "§${(i + 1).toString(16)}" }

    fun render(player: Player, request: ScoreboardRequest, previous: ScoreboardViewState?): ScoreboardViewState {
        val manager = Bukkit.getScoreboardManager()
        val board = previous?.board
            ?: player.scoreboard.takeIf { it != manager.mainScoreboard }
            ?: manager.newScoreboard
        val objective = previous?.objective ?: ensureObjective(board, request.title)

        if (previous == null || previous.title != request.title) {
            objective.displayName(request.title)
        }
        val sidebar = request.sidebar
        val slot = if (sidebar) DisplaySlot.SIDEBAR else DisplaySlot.PLAYER_LIST
        if (previous == null || previous.sidebar != sidebar) {
            objective.displaySlot = slot
        }

        val state = previous ?: ScoreboardViewState(
            board = board,
            objective = objective,
            sidebar = sidebar,
            title = request.title,
            linesByIndex = HashMap(16)
        )
        state.sidebar = sidebar
        state.title = request.title

        val requested = request.lines.take(15)
        for (index in 0 until 15) {
            val old = state.linesByIndex[index]
            val next = requested.getOrNull(index)
            if (next == null) {
                if (old != null) {
                    val entry = ENTRIES[index]
                    board.resetScores(entry)
                    board.getTeam(entry)?.unregister()
                    state.linesByIndex.remove(index)
                }
                continue
            }

            if (old == next) continue

            val entry = ENTRIES[index]
            val score = 15 - index
            objective.getScore(entry).score = score
            val team = getOrCreateTeam(board, entry)
            team.addEntry(entry)
            team.prefix(next)
            state.linesByIndex[index] = next
        }

        if (player.scoreboard !== board) {
            player.scoreboard = board
        }
        return state
    }

    private fun ensureObjective(scoreboard: org.bukkit.scoreboard.Scoreboard, title: Component): Objective {
        val existing = scoreboard.getObjective(OBJECTIVE_NAME)
        if (existing != null) return existing
        return scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title)
    }

    private fun getOrCreateTeam(scoreboard: org.bukkit.scoreboard.Scoreboard, name: String): org.bukkit.scoreboard.Team {
        return scoreboard.getTeam(name) ?: scoreboard.registerNewTeam(name)
    }
}

private class HudMetrics {
    val submitted = LongAdder()
    val rejectedByRateLimit = LongAdder()
    val droppedByPolicy = LongAdder()
    val replacedByCoalesce = LongAdder()
    val queueOverflowDropped = LongAdder()
    val preemptions = LongAdder()

    fun snapshot(): HudMetricsSnapshot = HudMetricsSnapshot(
        submitted = submitted.sum(),
        rejectedByRateLimit = rejectedByRateLimit.sum(),
        droppedByPolicy = droppedByPolicy.sum(),
        replacedByCoalesce = replacedByCoalesce.sum(),
        queueOverflowDropped = queueOverflowDropped.sum(),
        preemptions = preemptions.sum()
    )
}
