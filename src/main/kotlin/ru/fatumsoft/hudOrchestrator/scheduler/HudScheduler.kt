package ru.fatumsoft.hudOrchestrator.scheduler

import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Scheduler boundary for HudOrchestrator.
 *
 * Folia separates global-region work, entity-owned work and asynchronous work.
 * Keeping those calls behind this interface prevents queue/business logic from
 * depending directly on platform scheduler details.
 */
interface HudScheduler {
    fun runGlobal(task: () -> Unit): ScheduledHudTask

    fun runGlobalRepeating(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledHudTask

    fun runPlayer(player: Player, task: () -> Unit, retired: (() -> Unit)? = null): ScheduledHudTask?

    fun runPlayerDelayed(player: Player, delayTicks: Long, task: () -> Unit, retired: (() -> Unit)? = null): ScheduledHudTask?

    fun runPlayerRepeating(
        player: Player,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: () -> Unit,
        retired: (() -> Unit)? = null
    ): ScheduledHudTask?

    fun <T> supplyPlayer(player: Player, task: () -> T, retired: (() -> Unit)? = null): CompletableFuture<T>

    fun runAsync(task: () -> Unit): ScheduledHudTask

    fun runAsyncDelayed(delay: Long, unit: TimeUnit, task: () -> Unit): ScheduledHudTask

    fun runAsyncRepeating(initialDelay: Long, period: Long, unit: TimeUnit, task: () -> Unit): ScheduledHudTask

    fun cancelAll()
}
