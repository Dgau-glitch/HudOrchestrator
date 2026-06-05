package ru.fatumsoft.hudOrchestrator.core

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import ru.fatumsoft.hudOrchestrator.api.ActionBarRequest
import ru.fatumsoft.hudOrchestrator.api.ScoreboardRequest
import ru.fatumsoft.hudOrchestrator.api.TitleRequest
import java.time.Duration

internal data class ScoreboardViewState(
    val board: org.bukkit.scoreboard.Scoreboard,
    val objective: Objective,
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

    fun sendActionBar(player: Player, request: ActionBarRequest) {
        requireEntityThread(player)
        player.sendActionBar(request.content)
    }

    fun showTitle(player: Player, request: TitleRequest) {
        requireEntityThread(player)
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

    fun currentScoreboard(player: Player): org.bukkit.scoreboard.Scoreboard {
        requireEntityThread(player)
        return player.scoreboard
    }

    fun restoreScoreboard(player: Player, previousScoreboard: org.bukkit.scoreboard.Scoreboard?) {
        requireEntityThread(player)
        if (previousScoreboard != null && player.scoreboard != previousScoreboard) {
            player.scoreboard = previousScoreboard
        }
    }

    fun ensureScoreboardVisible(player: Player, state: ScoreboardViewState?) {
        requireEntityThread(player)
        val board = state?.board ?: return
        if (player.scoreboard !== board) {
            player.scoreboard = board
        }
    }

    fun renderScoreboard(player: Player, request: ScoreboardRequest, previous: ScoreboardViewState?): ScoreboardViewState {
        requireEntityThread(player)
        val manager = Bukkit.getScoreboardManager()
        val board = previous?.board ?: manager.newScoreboard
        val objective = previous?.objective ?: ensureObjective(board, request.title)

        if (previous == null || previous.title != request.title) {
            objective.displayName(request.title)
        }
        val sidebar = request.sidebar
        val slot = if (sidebar) DisplaySlot.SIDEBAR else DisplaySlot.PLAYER_LIST
        if (previous == null || previous.sidebar != sidebar) {
            objective.displaySlot = slot
        }

        val state = previous ?: ScoreboardViewState(
            board = board,
            objective = objective,
            sidebar = sidebar,
            title = request.title,
            linesByIndex = HashMap(16)
        )
        state.sidebar = sidebar
        state.title = request.title

        val requested = request.lines.take(15)
        for (index in 0 until 15) {
            val old = state.linesByIndex[index]
            val next = requested.getOrNull(index)
            if (next == null) {
                if (old != null) {
                    val entry = ENTRIES[index]
                    board.resetScores(entry)
                    board.getTeam(entry)?.unregister()
                    state.linesByIndex.remove(index)
                }
                continue
            }

            if (old == next) continue

            val entry = ENTRIES[index]
            val score = 15 - index
            objective.getScore(entry).score = score
            val team = getOrCreateTeam(board, entry)
            team.addEntry(entry)
            team.prefix(next)
            state.linesByIndex[index] = next
        }

        if (player.scoreboard !== board) {
            player.scoreboard = board
        }
        return state
    }

    private fun requireEntityThread(player: Player) {
        check(Bukkit.isOwnedByCurrentRegion(player)) {
            "Player HUD rendering must run on the player's Folia entity thread"
        }
    }

    private fun ensureObjective(scoreboard: org.bukkit.scoreboard.Scoreboard, title: Component): Objective {
        val existing = scoreboard.getObjective(OBJECTIVE_NAME)
        if (existing != null) return existing
        return scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title)
    }

    private fun getOrCreateTeam(scoreboard: org.bukkit.scoreboard.Scoreboard, name: String): org.bukkit.scoreboard.Team {
        return scoreboard.getTeam(name) ?: scoreboard.registerNewTeam(name)
    }
}
