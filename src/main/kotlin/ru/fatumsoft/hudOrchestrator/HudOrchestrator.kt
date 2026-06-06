package ru.fatumsoft.hudOrchestrator

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.fatumsoft.hudOrchestrator.api.HudOrchestratorApi
import ru.fatumsoft.hudOrchestrator.command.HudOrchestratorCommand
import ru.fatumsoft.hudOrchestrator.core.ChannelRateLimitConfig
import ru.fatumsoft.hudOrchestrator.core.HudOrchestratorService
import ru.fatumsoft.hudOrchestrator.core.SourcePolicyOverride
import ru.fatumsoft.hudOrchestrator.core.HudOrchestratorRuntimeConfig
import ru.fatumsoft.hudOrchestrator.scheduler.FoliaHudScheduler
import java.util.concurrent.CompletableFuture

class HudOrchestrator : JavaPlugin() {

    private lateinit var orchestratorService: HudOrchestratorService
    private val lifecycleScheduler by lazy { FoliaHudScheduler(this) }

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

    fun reloadOrchestratorConfig(): CompletableFuture<Unit> = reloadOrchestratorConfigAsync()

    fun reloadOrchestratorConfigAsync(): CompletableFuture<Unit> {
        if (!isEnabled || Bukkit.isStopping()) return CompletableFuture.completedFuture(Unit)
        val future = CompletableFuture<Unit>()
        lifecycleScheduler.runGlobal {
            try {
                reloadConfig()
                restartService()
                future.complete(Unit)
            } catch (throwable: Throwable) {
                future.completeExceptionally(throwable)
            }
        }
        return future
    }

    fun sendCommandFeedback(sender: CommandSender, message: String) {
        if (!isEnabled || Bukkit.isStopping()) {
            if (sender !is Player) sender.sendMessage(message)
            return
        }
        if (sender is Player) {
            lifecycleScheduler.runPlayer(sender, {
                sender.sendMessage(message)
            })
            return
        }
        lifecycleScheduler.runGlobal {
            sender.sendMessage(message)
        }
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


        fun loadSourceOverrides(): List<SourcePolicyOverride> {
            val section = config.getConfigurationSection("source-overrides") ?: return emptyList()
            return section.getKeys(false).mapNotNull { key ->
                val row = section.getConfigurationSection(key) ?: return@mapNotNull null
                val policy = row.getString("policy")?.let { runCatching { ru.fatumsoft.hudOrchestrator.api.DeliveryPolicy.valueOf(it.uppercase()) }.getOrNull() }
                SourcePolicyOverride(
                    pattern = row.getString("pattern", key) ?: key,
                    priority = row.getInt("priority").takeIf { row.contains("priority") },
                    policy = policy,
                    sourceCooldownTicks = row.getInt("source-cooldown-ticks").takeIf { row.contains("source-cooldown-ticks") },
                    stickinessTicks = row.getInt("stickiness-ticks").takeIf { row.contains("stickiness-ticks") },
                    dominanceTicks = row.getInt("dominance-ticks").takeIf { row.contains("dominance-ticks") }
                )
            }
        }

        return HudOrchestratorRuntimeConfig(
            actionBarRateLimit = loadRateLimit("rate-limit.action-bar", 20.0, 10.0),
            titleRateLimit = loadRateLimit("rate-limit.title", 5.0, 2.0),
            scoreboardRateLimit = loadRateLimit("rate-limit.scoreboard", 4.0, 1.0),
            queueLoggingEnabled = config.getBoolean("debug.queue-logging", false),
            sourcePolicyOverrides = loadSourceOverrides(),
            actionBarFallbackIdleGraceTicks = config.getInt("action-bar.fallback-idle-grace-ticks", 6).coerceAtLeast(0),
            packetFirewallMinPriority = config.getInt("strict-dominance.packet-firewall-min-priority", 75)
        )
    }
}
