package org.kami.exfilCraft.raid.model

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.boss.BossBar
import org.bukkit.boss.BarColor
import java.util.UUID

enum class RaidState { WAITING, ACTIVE, ENDED }

data class DCInfo(
    var loc: Location,
    var leftAt: Long,
    var forfeited: Boolean = false,
    var items: List<org.bukkit.inventory.ItemStack> = emptyList(),
    var dropped: Boolean = false
)

data class RaidSession(
    val id: Long,
    val world: World,
    val players: MutableSet<UUID>,
    var state: RaidState,
    val startedAtEpoch: Long,
    val durationSeconds: Int,
    var extractionAvailableAt: Long,
    val extractionPoint: Location,
    val extractionRadius: Double,
    val channelSecondsRequired: Int,
    val bossBar: BossBar,
    val perPlayerChannelRemaining: MutableMap<UUID, Int> = mutableMapOf(),
    val extracted: MutableSet<UUID> = mutableSetOf(),
    val dead: MutableSet<UUID> = mutableSetOf(),
    var extractionAnnounced: Boolean = false,
    var finalPhaseAnnounced: Boolean = false,
    val disconnectInfo: MutableMap<UUID, DCInfo> = mutableMapOf(),
    val spawnProtectionEndsAt: Long,
    val bossBarBaseColor: BarColor,
    val templateId: String,
    val unlockCondition: ExtractionUnlockCondition,
    var conditionalUnlocked: Boolean = false
) {
    fun remainingSeconds(now: Long): Int { val endAt = startedAtEpoch + durationSeconds; val rem = (endAt - now).toInt(); return if (rem < 0) 0 else rem }
    fun isExtractionUnlocked(now: Long): Boolean = when (unlockCondition) {
        ExtractionUnlockCondition.TIME -> now >= extractionAvailableAt
        ExtractionUnlockCondition.DRAGON_DEFEATED -> conditionalUnlocked
    }
    fun activePlayers(): Set<UUID> = players - extracted - dead
    fun isComplete(): Boolean = state == RaidState.ENDED || activePlayers().isEmpty()
    fun participantsRemaining(now: Long): Int = players.count { it !in extracted && it !in dead && !(disconnectInfo[it]?.forfeited == true) }
}

