package ru.fatumsoft.hudOrchestrator.core

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import ru.fatumsoft.hudOrchestrator.api.ActionBarRequest
import ru.fatumsoft.hudOrchestrator.api.DeliveryPolicy
import ru.fatumsoft.hudOrchestrator.api.HudChannel
import ru.fatumsoft.hudOrchestrator.api.HudHandle
import ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi
import ru.fatumsoft.hudOrchestrator.api.ScoreboardRequest
import ru.fatumsoft.hudOrchestrator.api.TitleRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class HudOrchestratorService(
    private val plugin: JavaPlugin
) : HudOrchestratorApi {

    private val states = ConcurrentHashMap<UUID, PlayerHudState>()
    private val sequence = AtomicLong(0L)
    private var task: BukkitTask? = null

    fun start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun shutdown() {
        task?.cancel()
        task = null
        states.values.forEach { it.clearVisualState() }
        states.clear()
    }

    override fun submitActionBar(playerId: UUID, request: ActionBarRequest): HudHandle? {
        if (!isAccepted(playerId, HudChannel.ACTION_BAR, request.meta.sourceId, request.meta.sourceCooldownTicks)) return null
        val nowTick = currentTick()
        val entry = QueueEntry.ActionBar(
            request = request,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.ACTION_BAR, request.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(request.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        playerState(playerId).actionBarQueue.offer(entry)
        return entry.handle
    }

    override fun submitTitle(playerId: UUID, request: TitleRequest): HudHandle? {
        if (!isAccepted(playerId, HudChannel.TITLE, request.meta.sourceId, request.meta.sourceCooldownTicks)) return null
        val nowTick = currentTick()
        val entry = QueueEntry.Title(
            request = request,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.TITLE, request.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(request.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        playerState(playerId).titleQueue.offer(entry)
        return entry.handle
    }

    override fun submitScoreboard(playerId: UUID, request: ScoreboardRequest): HudHandle? {
        if (!isAccepted(playerId, HudChannel.SCOREBOARD, request.meta.sourceId, request.meta.sourceCooldownTicks)) return null
        val nowTick = currentTick()
        val entry = QueueEntry.Scoreboard(
            request = request,
            handle = HudHandle(UUID.randomUUID(), playerId, HudChannel.SCOREBOARD, request.meta.sourceId),
            createdTick = nowTick,
            expireTick = nowTick + max(request.meta.ttlTicks, 1),
            seq = sequence.incrementAndGet()
        )
        playerState(playerId).scoreboardQueue.offer(entry)
        return entry.handle
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
        states.remove(playerId)?.clearVisualState()
    }

    private fun tick() {
        val nowTick = currentTick()
        val online = Bukkit.getOnlinePlayers()
        if (online.isEmpty()) return

        online.forEach { player ->
            val state = states[player.uniqueId] ?: return@forEach
            state.process(player, nowTick)
        }

        cleanupOffline(online.mapTo(hashSetOf()) { it.uniqueId })
    }

    private fun cleanupOffline(onlineIds: Set<UUID>) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in onlineIds) {
                entry.value.clearVisualState()
                iterator.remove()
            }
        }
    }

    private fun playerState(playerId: UUID): PlayerHudState {
        return states.computeIfAbsent(playerId) { PlayerHudState() }
    }

    private fun isAccepted(playerId: UUID, channel: HudChannel, sourceId: String, cooldownTicks: Int): Boolean {
        val state = playerState(playerId)
        return state.rateLimiter.accept(channel, sourceId, currentTick(), cooldownTicks)
    }

    private fun currentTick(): Long = Bukkit.getCurrentTick().toLong()
}

private class PlayerHudState {
    val actionBarQueue = HudQueue<QueueEntry.ActionBar>(maxSize = 64)
    val titleQueue = HudQueue<QueueEntry.Title>(maxSize = 32)
    val scoreboardQueue = HudQueue<QueueEntry.Scoreboard>(maxSize = 32)
    val rateLimiter = PlayerRateLimiter()

    private var activeActionBar: ActiveEntry<QueueEntry.ActionBar>? = null
    private var activeTitle: ActiveEntry<QueueEntry.Title>? = null
    private var activeScoreboard: ActiveEntry<QueueEntry.Scoreboard>? = null
    private var scoreboardOwner: String? = null

    fun process(player: Player, nowTick: Long) {
        processActionBar(player, nowTick)
        processTitle(player, nowTick)
        processScoreboard(player, nowTick)
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

    fun clearVisualState() {
        actionBarQueue.clear()
        titleQueue.clear()
        scoreboardQueue.clear()
        activeActionBar = null
        activeTitle = null
        activeScoreboard = null
    }

    private fun processActionBar(player: Player, nowTick: Long) {
        actionBarQueue.discardExpired(nowTick)
        val current = activeActionBar
        if (current != null && current.isExpired(nowTick)) activeActionBar = null

        val selected = selectNext(actionBarQueue, activeActionBar, nowTick)
        if (selected != null && shouldActivate(selected, activeActionBar, nowTick)) {
            activeActionBar = ActiveEntry(selected, nowTick + max(selected.request.meta.minShowTicks.toLong(), 1L), nowTick + max(selected.request.meta.maxShowTicks.toLong(), 1L), nowTick)
            selected.send(player)
        } else {
            val active = activeActionBar ?: return
            val resendEvery = max(active.entry.request.resendIntervalTicks, 1)
            if (nowTick - active.lastSendTick >= resendEvery) {
                active.entry.send(player)
                active.lastSendTick = nowTick
            }
        }
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
                selected.send(player)
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
            return null
        }

        if (policy == DeliveryPolicy.PREEMPT) {
            queue.remove(best.handle.id)
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

    fun offer(entry: T) {
        val meta = entry.requestMeta()
        val replaceBy = meta.dedupKey ?: meta.replaceGroup
        if (meta.policy == DeliveryPolicy.COALESCE && replaceBy != null) {
            val idx = queue.indexOfFirst { it.sourceId() == entry.sourceId() && (it.requestMeta().dedupKey == replaceBy || it.requestMeta().replaceGroup == replaceBy) }
            if (idx >= 0) {
                queue[idx] = entry
                return
            }
        }

        if (queue.size >= maxSize) {
            val lowestIdx = queue.indices.minByOrNull { queue[it].priority() } ?: -1
            if (lowestIdx >= 0 && queue[lowestIdx].priority() < entry.priority()) {
                queue[lowestIdx] = entry
            }
            return
        }

        queue += entry
    }

    fun remove(handleId: UUID): Boolean = queue.removeIf { it.handle.id == handleId }

    fun removeBySource(sourceId: String): Int {
        val before = queue.size
        queue.removeIf { it.sourceId() == sourceId }
        return before - queue.size
    }

    fun discardExpired(nowTick: Long) {
        queue.removeIf { it.expireTick <= nowTick }
    }

    fun bestCandidate(nowTick: Long): T? {
        var best: T? = null
        var bestScore = Int.MIN_VALUE
        for (entry in queue) {
            val wait = (nowTick - entry.createdTick).coerceAtLeast(0)
            val effective = entry.priority() + (wait / 20L).toInt()
            if (effective > bestScore || (effective == bestScore && (best == null || entry.seq < best.seq))) {
                best = entry
                bestScore = effective
            }
        }
        return best
    }

    fun clear() = queue.clear()
}

private class PlayerRateLimiter {
    private val stateBySourceAndChannel = HashMap<String, SourceLimiterState>(32)

    fun accept(channel: HudChannel, sourceId: String, nowTick: Long, cooldownTicks: Int): Boolean {
        val key = "${channel.name}:$sourceId"
        val state = stateBySourceAndChannel.computeIfAbsent(key) { SourceLimiterState(nowTick) }

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
            val manager = Bukkit.getScoreboardManager()
            val board = player.scoreboard.takeIf { it != manager.mainScoreboard } ?: manager.newScoreboard
            val objective = ensureObjective(board)

            objective.displayName(request.title)
            objective.displaySlot = if (request.sidebar) DisplaySlot.SIDEBAR else DisplaySlot.PLAYER_LIST

            // Remove previous scores from this objective
            board.entries.forEach { board.resetScores(it) }

            request.lines.take(15).forEachIndexed { index, component ->
                val entry = "§${(index + 1).toString(16)}"
                objective.getScore(entry).score = 15 - index
                board.getTeam(entry)?.unregister()
                val team = board.registerNewTeam(entry)
                team.addEntry(entry)
                team.prefix(component)
            }

            player.scoreboard = board
        }

        private fun ensureObjective(scoreboard: org.bukkit.scoreboard.Scoreboard): Objective {
            val existing = scoreboard.getObjective(OBJECTIVE_NAME)
            if (existing != null) return existing
            return scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, request.title)
        }

        companion object {
            private const val OBJECTIVE_NAME = "hud_orchestrator"
        }
    }
}
