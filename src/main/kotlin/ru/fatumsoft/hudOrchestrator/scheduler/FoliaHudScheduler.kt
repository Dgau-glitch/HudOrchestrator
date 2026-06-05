package ru.fatumsoft.hudOrchestrator.scheduler

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Folia-backed scheduler implementation.
 *
 * Uses:
 * - GlobalRegionScheduler for global plugin lifecycle/tick bookkeeping;
 * - EntityScheduler for player-owned HUD operations;
 * - AsyncScheduler for non-Bukkit asynchronous work.
 */
class FoliaHudScheduler(private val plugin: Plugin) : HudScheduler {
    override fun runGlobal(task: () -> Unit): ScheduledHudTask {
        val scheduled = plugin.server.globalRegionScheduler.run(plugin) { task() }
        return scheduled.asHudTask()
    }

    override fun runGlobalRepeating(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledHudTask {
        val scheduled = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { task() },
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L)
        )
        return scheduled.asHudTask()
    }

    override fun runPlayer(player: Player, task: () -> Unit, retired: (() -> Unit)?): ScheduledHudTask? {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            task()
            return ScheduledHudTask { }
        }
        val scheduled = player.scheduler.run(plugin, { task() }, retired?.let { Runnable { it() } })
        return scheduled?.asHudTask()
    }

    override fun runPlayerDelayed(player: Player, delayTicks: Long, task: () -> Unit, retired: (() -> Unit)?): ScheduledHudTask? {
        val scheduled = player.scheduler.runDelayed(
            plugin,
            { task() },
            retired?.let { Runnable { it() } },
            delayTicks.coerceAtLeast(1L)
        )
        return scheduled?.asHudTask()
    }

    override fun runPlayerRepeating(
        player: Player,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: () -> Unit,
        retired: (() -> Unit)?
    ): ScheduledHudTask? {
        val scheduled = player.scheduler.runAtFixedRate(
            plugin,
            { task() },
            retired?.let { Runnable { it() } },
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L)
        )
        return scheduled?.asHudTask()
    }

    override fun <T> supplyPlayer(player: Player, task: () -> T, retired: (() -> Unit)?): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            completeFuture(future, task)
            return future
        }

        val scheduled = player.scheduler.run(
            plugin,
            {
                completeFuture(future, task)
            },
            Runnable {
                retired?.invoke()
                future.completeExceptionally(IllegalStateException("Player scheduler retired before HudOrchestrator task could run"))
            }
        )
        if (scheduled == null) {
            retired?.invoke()
            future.completeExceptionally(IllegalStateException("Player scheduler is retired"))
        }
        return future
    }

    override fun runAsync(task: () -> Unit): ScheduledHudTask {
        val scheduled = plugin.server.asyncScheduler.runNow(plugin) { task() }
        return scheduled.asHudTask()
    }

    override fun runAsyncDelayed(delay: Long, unit: TimeUnit, task: () -> Unit): ScheduledHudTask {
        val scheduled = plugin.server.asyncScheduler.runDelayed(plugin, { task() }, delay, unit)
        return scheduled.asHudTask()
    }

    override fun runAsyncRepeating(initialDelay: Long, period: Long, unit: TimeUnit, task: () -> Unit): ScheduledHudTask {
        val scheduled = plugin.server.asyncScheduler.runAtFixedRate(plugin, { task() }, initialDelay, period, unit)
        return scheduled.asHudTask()
    }

    override fun cancelAll() {
        plugin.server.globalRegionScheduler.cancelTasks(plugin)
        plugin.server.asyncScheduler.cancelTasks(plugin)
    }

    private fun io.papermc.paper.threadedregions.scheduler.ScheduledTask.asHudTask(): ScheduledHudTask {
        return ScheduledHudTask { cancel() }
    }

    private fun <T> completeFuture(future: CompletableFuture<T>, task: () -> T) {
        try {
            future.complete(task())
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }
    }
}
