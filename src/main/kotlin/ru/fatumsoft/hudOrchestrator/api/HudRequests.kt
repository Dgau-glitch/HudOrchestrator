package ru.fatumsoft.hudOrchestrator.api

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.UUID

/**
 * Common metadata for all HUD requests.
 *
 * [sourceId] should be stable and unique for producer stream, recommended format:
 * `PluginName[:subsystem]`.
 *
 * This allows HudOrchestrator to auto-clean stale requests when a plugin is disabled/reloaded.
 */
data class HudRequestMeta(
    val sourceId: String,
    val priority: Int = Priority.NORMAL.weight,
    val policy: DeliveryPolicy = DeliveryPolicy.ENQUEUE,
    val dedupKey: String? = null,
    val replaceGroup: String? = null,
    val ttlTicks: Int = 40,
    val minShowTicks: Int = 10,
    val maxShowTicks: Int = 40,
    val sourceCooldownTicks: Int = 0,
    val stickinessTicks: Int = 0
)

data class ActionBarRequest(
    val content: Component,
    val meta: HudRequestMeta,
    val resendIntervalTicks: Int = 10
)

data class TitleRequest(
    val title: Component,
    val subtitle: Component = Component.empty(),
    val fadeInTicks: Int = 5,
    val stayTicks: Int = 40,
    val fadeOutTicks: Int = 10,
    val meta: HudRequestMeta
)

data class ScoreboardRequest(
    val title: Component,
    val lines: List<Component>,
    val meta: HudRequestMeta,
    val sidebar: Boolean = true,
    val ownerMode: Boolean = false
)

data class HudHandle(
    val id: UUID,
    val playerId: UUID,
    val channel: HudChannel,
    val sourceId: String
)

data class HudMetricsSnapshot(
    val submitted: Long,
    val rejectedByRateLimit: Long,
    val droppedByPolicy: Long,
    val replacedByCoalesce: Long,
    val queueOverflowDropped: Long,
    val preemptions: Long
)

/**
 * Public service contract that external plugins can retrieve from Bukkit ServicesManager.
 *
 * All methods are expected to be called from the server thread.
 */
interface HudOrchestratorApi {
    /**
     * Submit action bar message request.
     *
     * Returns null when request was rejected by anti-spam limits.
     */
    fun submitActionBar(playerId: UUID, request: ActionBarRequest): HudHandle?

    /**
     * Submit title request.
     *
     * Returns null when request was rejected by anti-spam limits.
     */
    fun submitTitle(playerId: UUID, request: TitleRequest): HudHandle?

    /**
     * Submit scoreboard request.
     *
     * Returns null when request was rejected by anti-spam limits.
     */
    fun submitScoreboard(playerId: UUID, request: ScoreboardRequest): HudHandle?

    /** Cancel a specific request by handle. */
    fun cancel(handle: HudHandle): Boolean

    /** Cancel queued/active requests for source and optional player/channel scope. */
    fun cancelBySource(sourceId: String, playerId: UUID? = null, channel: HudChannel? = null): Int

    /** Clear all queued/active HUD state for player. */
    fun clearPlayer(playerId: UUID)

    /** Current runtime metrics snapshot. */
    fun metricsSnapshot(): HudMetricsSnapshot

    /**
     * Thread-safe helper: executes submit on main thread when called asynchronously.
     */
    fun submitActionBarThreadSafe(plugin: Plugin, playerId: UUID, request: ActionBarRequest): CompletableFuture<HudHandle?> {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(submitActionBar(playerId, request))
        }

        val future = CompletableFuture<HudHandle?>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                future.complete(submitActionBar(playerId, request))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        })
        return future
    }

    /**
     * Thread-safe helper: executes submit on main thread when called asynchronously.
     */
    fun submitTitleThreadSafe(plugin: Plugin, playerId: UUID, request: TitleRequest): CompletableFuture<HudHandle?> {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(submitTitle(playerId, request))
        }

        val future = CompletableFuture<HudHandle?>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                future.complete(submitTitle(playerId, request))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        })
        return future
    }

    /**
     * Thread-safe helper: executes submit on main thread when called asynchronously.
     */
    fun submitScoreboardThreadSafe(plugin: Plugin, playerId: UUID, request: ScoreboardRequest): CompletableFuture<HudHandle?> {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(submitScoreboard(playerId, request))
        }

        val future = CompletableFuture<HudHandle?>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                future.complete(submitScoreboard(playerId, request))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        })
        return future
    }
}
