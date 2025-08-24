package org.kami.exfilCraft.raid.model

import org.bukkit.World
import org.bukkit.Material

enum class ExtractionUnlockCondition { TIME, DRAGON_DEFEATED }

data class RaidTemplate(
    val id: String,
    val displayName: String,
    val environment: World.Environment,
    val sizeChunks: Int,
    val durationMinutes: Int,
    val extractionOpenAfterSeconds: Int?, // null when conditional unlock
    val unlockCondition: ExtractionUnlockCondition,
    val minPlayers: Int,
    val maxPlayers: Int,
    val minTeams: Int = 1,
    val maxTeams: Int = Int.MAX_VALUE,
    val maxPlayersPerTeam: Int = Int.MAX_VALUE,
    val extractionRadiusOverride: Double? = null,
    val channelSecondsOverride: Int? = null,
    val spawnProtectionSecondsOverride: Int? = null,
    val forceNight: Boolean = false,
    val requireCenterStructure: Boolean = false,
    val requiredStructures: List<String> = emptyList(),
    val centerRequiredStructures: List<String> = emptyList(),
    val centerRadiusChunks: Int = 4,
    val requireDragon: Boolean = false,
    val alwaysEndCities: Boolean = false,
    val maxWorldGenAttempts: Int = 6,
    val enabled: Boolean = true,
    val iconMaterial: Material? = null,
    val spacerAfter: Boolean = false,
    val displayItem: Material? = null,
    val order: Int = Int.MAX_VALUE
)
