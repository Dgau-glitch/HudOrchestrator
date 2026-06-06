package ru.fatumsoft.hudOrchestrator.core

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import com.github.retrooper.packetevents.protocol.score.ScoreFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.fatumsoft.hudOrchestrator.api.ActionBarRequest
import ru.fatumsoft.hudOrchestrator.api.ScoreboardRequest
import ru.fatumsoft.hudOrchestrator.api.HudChannel
import ru.fatumsoft.hudOrchestrator.api.TitleRequest
import java.time.Duration

internal data class ScoreboardViewState(
    var objectiveCreated: Boolean,
    var sidebar: Boolean,
    var title: Component,
    val linesByIndex: MutableMap<Int, Component>
)

/**
 * Player-owned HUD renderer.
 *
 * Every method validates Folia ownership before touching Player visual APIs.
 * Callers must schedule this renderer through the player's EntityScheduler.
 */
internal object PlayerHudRenderer {
    private const val OBJECTIVE_NAME = "hud_orchestrator"
    private val ENTRIES = Array(15) { i -> "§${(i + 1).toString(16)}" }
    private val TEAM_NAMES = Array(15) { i -> "ho_${i.toString().padStart(2, '0')}" }

    fun sendActionBar(player: Player, request: ActionBarRequest) {
        requireEntityThread(player)
        HudPacketFirewall.allowHudPacket(player.uniqueId, HudChannel.ACTION_BAR, packetCount = 2) {
            sendPacket(player, WrapperPlayServerSystemChatMessage(true, request.content))
            sendPacket(player, WrapperPlayServerActionBar(request.content))
        }
    }

    fun showTitle(player: Player, request: TitleRequest) {
        requireEntityThread(player)
        HudPacketFirewall.allowHudPacket(player.uniqueId, HudChannel.TITLE) {
            player.showTitle(
                Title.title(
                    request.title,
                    request.subtitle,
                    Title.Times.times(
                        Duration.ofMillis((request.fadeInTicks * 50L).coerceAtLeast(0L)),
                        Duration.ofMillis((request.stayTicks * 50L).coerceAtLeast(0L)),
                        Duration.ofMillis((request.fadeOutTicks * 50L).coerceAtLeast(0L))
                    )
                )
            )
        }
    }

    fun ensureScoreboardVisible(player: Player, state: ScoreboardViewState?) {
        requireEntityThread(player)
        val view = state ?: return
        if (view.objectiveCreated) {
            sendPacket(player, WrapperPlayServerDisplayScoreboard(if (view.sidebar) SIDEBAR_SLOT else PLAYER_LIST_SLOT, OBJECTIVE_NAME))
        }
    }

    fun clearScoreboard(player: Player, state: ScoreboardViewState?) {
        requireEntityThread(player)
        val view = state ?: return
        for (index in ENTRIES.indices) {
            resetScore(player, index)
            removeTeam(player, index)
        }
        view.linesByIndex.clear()
        if (view.objectiveCreated) {
            sendPacket(
                player,
                WrapperPlayServerScoreboardObjective(
                    OBJECTIVE_NAME,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                    Component.empty(),
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                    BLANK_SCORE_FORMAT
                )
            )
            view.objectiveCreated = false
        }
    }

    fun renderScoreboard(player: Player, request: ScoreboardRequest, previous: ScoreboardViewState?): ScoreboardViewState {
        requireEntityThread(player)
        // Folia throws UnsupportedOperationException for several Bukkit scoreboard operations.
        // PacketEvents sends only clientbound scoreboard packets and does not mutate Bukkit scoreboard state.
        val state = previous ?: ScoreboardViewState(
            objectiveCreated = false,
            sidebar = request.sidebar,
            title = request.title,
            linesByIndex = HashMap(16)
        )

        if (!state.objectiveCreated) {
            sendPacket(
                player,
                WrapperPlayServerScoreboardObjective(
                    OBJECTIVE_NAME,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                    Component.empty(),
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                    BLANK_SCORE_FORMAT
                )
            )
            sendPacket(
                player,
                WrapperPlayServerScoreboardObjective(
                    OBJECTIVE_NAME,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                    request.title,
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                    BLANK_SCORE_FORMAT
                )
            )
            state.objectiveCreated = true
        } else if (state.title != request.title) {
            sendPacket(
                player,
                WrapperPlayServerScoreboardObjective(
                    OBJECTIVE_NAME,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE,
                    request.title,
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                    BLANK_SCORE_FORMAT
                )
            )
        }

        val slot = if (request.sidebar) SIDEBAR_SLOT else PLAYER_LIST_SLOT
        if (previous == null || state.sidebar != request.sidebar) {
            sendPacket(player, WrapperPlayServerDisplayScoreboard(slot, OBJECTIVE_NAME))
        }
        state.sidebar = request.sidebar
        state.title = request.title

        val requested = request.lines.take(15)
        for (index in ENTRIES.indices) {
            val old = state.linesByIndex[index]
            val next = requested.getOrNull(index)
            val entry = ENTRIES[index]
            val teamName = TEAM_NAMES[index]

            if (next == null) {
                if (old != null) {
                    resetScore(player, index)
                    removeTeam(player, index)
                    state.linesByIndex.remove(index)
                }
                continue
            }

            if (old != next) {
                val mode = if (old == null) WrapperPlayServerTeams.TeamMode.CREATE else WrapperPlayServerTeams.TeamMode.UPDATE
                if (old == null) {
                    removeTeam(player, index)
                }
                sendPacket(player, WrapperPlayServerTeams(teamName, mode, teamInfo(next), entry))
                state.linesByIndex[index] = next
            }

            if (old == null) {
                sendPacket(
                    player,
                    WrapperPlayServerUpdateScore(
                        entry,
                        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                        OBJECTIVE_NAME,
                        15 - index,
                        Component.empty(),
                        BLANK_SCORE_FORMAT
                    )
                )
            }
        }

        return state
    }

    private fun resetScore(player: Player, index: Int) {
        sendPacket(player, WrapperPlayServerResetScore(ENTRIES[index], OBJECTIVE_NAME))
    }

    private fun removeTeam(player: Player, index: Int) {
        sendPacket(
            player,
            WrapperPlayServerTeams(
                TEAM_NAMES[index],
                WrapperPlayServerTeams.TeamMode.REMOVE,
                null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
                ENTRIES[index]
            )
        )
    }

    private fun requireEntityThread(player: Player) {
        check(Bukkit.isOwnedByCurrentRegion(player)) {
            "Player HUD rendering must run on the player's Folia entity thread"
        }
    }

    private fun teamInfo(line: Component): WrapperPlayServerTeams.ScoreBoardTeamInfo {
        return WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            line,
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE
        )
    }

    private fun sendPacket(player: Player, packet: PacketWrapper<*>) {
        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
    }

    private val BLANK_SCORE_FORMAT = ScoreFormat.blankScore()

    private const val PLAYER_LIST_SLOT = 0
    private const val SIDEBAR_SLOT = 1
}
