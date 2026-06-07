package ru.fatumsoft.hudOrchestrator.scheduler

/**
 * Lightweight wrapper around platform-specific scheduled tasks.
 */
fun interface ScheduledHudTask {
    fun cancel()
}
