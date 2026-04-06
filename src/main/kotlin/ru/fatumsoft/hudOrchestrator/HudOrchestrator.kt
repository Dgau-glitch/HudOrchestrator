package ru.fatumsoft.hudOrchestrator

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi
import ru.fatumsoft.hudOrchestrator.command.HudOrchestratorCommand
import ru.fatumsoft.hudOrchestrator.core.ChannelRateLimitConfig
import ru.fatumsoft.hudOrchestrator.core.HudOrchestratorService
import ru.fatumsoft.hudOrchestrator.core.HudOrchestratorRuntimeConfig

class HudOrchestrator : JavaPlugin() {

    private lateinit var orchestratorService: HudOrchestratorService

    override fun onEnable() {
        saveDefaultConfig()
        restartService()

        val command = HudOrchestratorCommand(this)
        getCommand("hudorchestrator")?.setExecutor(command)
        getCommand("hudorchestrator")?.tabCompleter = command

        logger.info("HudOrchestrator enabled")
    }

    override fun onDisable() {
        stopService()
        logger.info("HudOrchestrator disabled")
    }

    fun reloadOrchestratorConfig() {
        reloadConfig()
        restartService()
    }

    private fun restartService() {
        if (this::orchestratorService.isInitialized) {
            stopService()
        }
        orchestratorService = HudOrchestratorService(this, loadRuntimeConfig())
        orchestratorService.start()
        server.servicesManager.register(
            HudOrchestratorApi::class.java,
            orchestratorService,
            this,
            ServicePriority.Highest
        )
    }

    private fun stopService() {
        if (!this::orchestratorService.isInitialized) return
        server.servicesManager.unregister(HudOrchestratorApi::class.java, orchestratorService)
        orchestratorService.shutdown()
    }

    private fun loadRuntimeConfig(): HudOrchestratorRuntimeConfig {
        fun loadRateLimit(path: String, defaultCapacity: Double, defaultRefill: Double): ChannelRateLimitConfig {
            val capacity = config.getDouble("$path.capacity", defaultCapacity).coerceAtLeast(1.0)
            val refill = config.getDouble("$path.refill-per-second", defaultRefill).coerceAtLeast(0.1)
            return ChannelRateLimitConfig(capacity = capacity, refillPerSecond = refill)
        }

        return HudOrchestratorRuntimeConfig(
            actionBarRateLimit = loadRateLimit("rate-limit.action-bar", 20.0, 10.0),
            titleRateLimit = loadRateLimit("rate-limit.title", 5.0, 2.0),
            scoreboardRateLimit = loadRateLimit("rate-limit.scoreboard", 4.0, 1.0),
            queueLoggingEnabled = config.getBoolean("debug.queue-logging", false)
        )
    }
}
