package ru.fatumsoft.hudOrchestrator.core

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import org.bukkit.entity.Player
import ru.fatumsoft.hudOrchestrator.api.HudChannel
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outbound packet guard for strict QuestCore dominance windows.
 *
 * HudOrchestrator-owned sends are wrapped through [allowHudPacket]. External packets
 * for the same player/channel are cancelled while a strict dominance source is active.
 */
internal object HudPacketFirewall : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    private const val TICK_MILLIS = 50L
    private val registered = AtomicBoolean(false)
    private val suppressedUntilMillis = ConcurrentHashMap<UUID, EnumMap<HudChannel, Long>>()
    private val allowedPackets = ThreadLocal.withInitial { HashMap<PacketAllowance, Int>() }

    fun register() {
        if (registered.compareAndSet(false, true)) {
            PacketEvents.getAPI().eventManager.registerListener(this)
        }
    }

    fun unregister() {
        if (registered.compareAndSet(true, false)) {
            PacketEvents.getAPI().eventManager.unregisterListener(this)
            suppressedUntilMillis.clear()
        }
    }

    fun suppress(playerId: UUID, channel: HudChannel, ticks: Long) {
        if (ticks <= 0L) return
        val untilMillis = System.currentTimeMillis() + ticks * TICK_MILLIS
        suppressedUntilMillis.compute(playerId) { _, current ->
            val map = current ?: EnumMap(HudChannel::class.java)
            map[channel] = maxOf(map[channel] ?: 0L, untilMillis)
            map
        }
    }

    fun <T> allowHudPacket(playerId: UUID, channel: HudChannel, block: () -> T): T {
        val key = PacketAllowance(playerId, channel)
        val map = allowedPackets.get()
        map[key] = (map[key] ?: 0) + 1
        return try {
            block()
        } finally {
            val next = (map[key] ?: 1) - 1
            if (next <= 0) map.remove(key) else map[key] = next
            if (map.isEmpty()) allowedPackets.remove()
        }
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val channel = event.toHudChannel() ?: return
        val player = event.getPlayer<Player>()
        val playerId = player.uniqueId
        if (isAllowed(playerId, channel)) return
        val untilMillis = suppressedUntilMillis[playerId]?.get(channel) ?: return
        val nowMillis = System.currentTimeMillis()
        if (nowMillis >= untilMillis) {
            clearExpired(playerId, channel, nowMillis)
            return
        }
        event.isCancelled = true
    }

    private fun isAllowed(playerId: UUID, channel: HudChannel): Boolean {
        return (allowedPackets.get()[PacketAllowance(playerId, channel)] ?: 0) > 0
    }

    private fun clearExpired(playerId: UUID, channel: HudChannel, nowMillis: Long) {
        suppressedUntilMillis.computeIfPresent(playerId) { _, map ->
            if ((map[channel] ?: 0L) <= nowMillis) map.remove(channel)
            if (map.isEmpty()) null else map
        }
    }

    private fun PacketSendEvent.toHudChannel(): HudChannel? {
        return when (packetType) {
            PacketType.Play.Server.ACTION_BAR -> HudChannel.ACTION_BAR
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                if (WrapperPlayServerSystemChatMessage(this).isOverlay) HudChannel.ACTION_BAR else null
            }
            PacketType.Play.Server.TITLE,
            PacketType.Play.Server.SET_TITLE_TEXT,
            PacketType.Play.Server.SET_TITLE_SUBTITLE,
            PacketType.Play.Server.SET_TITLE_TIMES,
            PacketType.Play.Server.CLEAR_TITLES -> HudChannel.TITLE
            else -> null
        }
    }

    private data class PacketAllowance(val playerId: UUID, val channel: HudChannel)
}
