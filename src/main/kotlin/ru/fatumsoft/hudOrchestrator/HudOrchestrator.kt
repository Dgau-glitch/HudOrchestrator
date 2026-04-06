package ru.fatumsoft.hudOrchestrator

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi
import ru.fatumsoft.hudOrchestrator.core.HudOrchestratorService

class HudOrchestrator : JavaPlugin() {

    private lateinit var orchestratorService: HudOrchestratorService

    override fun onEnable() {
        orchestratorService = HudOrchestratorService(this)
        orchestratorService.start()

        server.servicesManager.register(
            HudOrchestratorApi::class.java,
            orchestratorService,
            this,
            ServicePriority.Highest
        )

        logger.info("HudOrchestrator enabled")
    }

    override fun onDisable() {
        server.servicesManager.unregister(HudOrchestratorApi::class.java, orchestratorService)
        orchestratorService.shutdown()
        logger.info("HudOrchestrator disabled")
    }
}
