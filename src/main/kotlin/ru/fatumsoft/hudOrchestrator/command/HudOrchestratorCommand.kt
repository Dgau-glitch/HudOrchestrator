package ru.fatumsoft.hudOrchestrator.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.fatumsoft.hudOrchestrator.HudOrchestrator

class HudOrchestratorCommand(
    private val plugin: HudOrchestrator
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            plugin.sendCommandFeedback(sender, "§cУ вас нет прав.")
            return true
        }

        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            plugin.sendCommandFeedback(sender, "§e/$label reload §7- перезагрузить конфиг и сервис HudOrchestrator")
            return true
        }

        return when (args[0].lowercase()) {
            "reload" -> {
                plugin.sendCommandFeedback(sender, "§eHudOrchestrator: reload запланирован на Folia global scheduler...")
                plugin.reloadOrchestratorConfigAsync().whenComplete { _, throwable ->
                    if (throwable != null) {
                        plugin.logger.severe("HudOrchestrator reload failed: ${throwable.message}")
                        plugin.sendCommandFeedback(sender, "§cHudOrchestrator: reload завершился ошибкой. Проверьте консоль.")
                    } else {
                        plugin.sendCommandFeedback(sender, "§aHudOrchestrator: конфиг и сервис перезагружены.")
                    }
                }
                true
            }
            else -> {
                plugin.sendCommandFeedback(sender, "§cНеизвестная подкоманда. Используйте: /$label reload")
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (!sender.hasPermission(PERMISSION_ADMIN)) return mutableListOf()
        if (args.size == 1) {
            val q = args[0].lowercase()
            return listOf("reload", "help").filter { it.startsWith(q) }.toMutableList()
        }
        return mutableListOf()
    }

    companion object {
        private const val PERMISSION_ADMIN = "hudorchestrator.admin"
    }
}
