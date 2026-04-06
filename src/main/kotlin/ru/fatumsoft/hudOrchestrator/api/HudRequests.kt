package ru.fatumsoft.hudOrchestrator.api

import net.kyori.adventure.text.Component
import java.util.UUID

data class HudRequestMeta(
    val sourceId: String,
    val priority: Int = Priority.NORMAL.weight,
    val policy: DeliveryPolicy = DeliveryPolicy.ENQUEUE,
    val dedupKey: String? = null,
    val replaceGroup: String? = null,
    val ttlTicks: Int = 40,
    val minShowTicks: Int = 10,
    val maxShowTicks: Int = 40,
    val sourceCooldownTicks: Int = 0
)

data class ActionBarRequest(
    val content: Component,
    val meta: HudRequestMeta,
    val resendIntervalTicks: Int = 10
)

data class TitleRequest(
    val title: Component,
    val subtitle: Component = Component.empty(),
    val fadeInTicks: Int = 5,
    val stayTicks: Int = 40,
    val fadeOutTicks: Int = 10,
    val meta: HudRequestMeta
)

data class ScoreboardRequest(
    val title: Component,
    val lines: List<Component>,
    val meta: HudRequestMeta,
    val sidebar: Boolean = true,
    val ownerMode: Boolean = false
)

data class HudHandle(
    val id: UUID,
    val playerId: UUID,
    val channel: HudChannel,
    val sourceId: String
)

interface HudOrchestratorApi {
    fun submitActionBar(playerId: UUID, request: ActionBarRequest): HudHandle?
    fun submitTitle(playerId: UUID, request: TitleRequest): HudHandle?
    fun submitScoreboard(playerId: UUID, request: ScoreboardRequest): HudHandle?

    fun cancel(handle: HudHandle): Boolean
    fun cancelBySource(sourceId: String, playerId: UUID? = null, channel: HudChannel? = null): Int
    fun clearPlayer(playerId: UUID)
}
