/* MIT License 2025 tsuki */
package org.kami.exfilCraft.core

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ConfigService(private val plugin: JavaPlugin) {
    private val debugFlag = AtomicBoolean(true)
    private val cfg: FileConfiguration get() = plugin.config
    val debugEnabled: Boolean get() = debugFlag.get()
    private var cachedRaidTemplates: List<org.kami.exfilCraft.raid.RaidTemplate>? = null

    init { load() }

    fun load() {
        plugin.reloadConfig()
        debugFlag.set(cfg.getBoolean("debug", true))
        cachedRaidTemplates = null // invalidate template cache on reload
    }
    fun reload() = load()

    // Logging
    val logPrefixRaw: String get() = cfg.getString("logging.prefix", "&7[&aExfilCraft&7]")!!
    val logColorize: Boolean get() = cfg.getBoolean("logging.colorize", true)
    val logDebugOverride: Boolean get() = cfg.getBoolean("logging.debug", debugEnabled)
    val logSpamSuppressionSeconds: Double get() = cfg.getDouble("logging.spamSuppressionSeconds", 1.5)

    // Base bunker settings
    val bunkerWorldName: String get() = cfg.getString("bunker.worldName", "exfil_bunkers")!!
    val bunkerStartY: Int get() = cfg.getInt("bunker.startY", 64)
    val bunkerHeight: Int get() = cfg.getInt("bunker.height", 16)
    val bunkerCubeSize: Int get() = cfg.getInt("bunker.expansionCubeSize", 16)
    val bunkerMaxCubes: Int get() = cfg.getInt("bunker.maxCubes", 512)
    val bunkerMinChunkSeparation: Int get() = cfg.getInt("bunker.minChunkSeparation", 64)
    val bunkerExpansionCooldownMinutes: Long get() = cfg.getLong("bunker.expansionCooldownMinutes", 10)
    val bunkerInviteExpiryMinutes: Long get() = cfg.getLong("bunker.inviteExpiryMinutes", 60)
    val expansionCostBaseXp: Int get() = cfg.getInt("bunker.expansionCostBaseXp", 100)
    val databaseFile: String get() = cfg.getString("database.file", "plugins/ExfilCraft/data.db")!!

    // Rule getters
    val rulePreventDamage get() = cfg.getBoolean("bunker.rules.preventDamage", true)
    val rulePreventHunger get() = cfg.getBoolean("bunker.rules.preventHunger", true)
    val ruleDisableMobSpawns get() = cfg.getBoolean("bunker.rules.disableMobSpawns", true)
    val ruleDisableDaylightCycle get() = cfg.getBoolean("bunker.rules.disableDaylightCycle", true)
    val ruleDisableWeatherCycle get() = cfg.getBoolean("bunker.rules.disableWeatherCycle", true)
    val ruleRandomTickSpeed get() = cfg.getInt("bunker.rules.randomTickSpeed", 0)
    val ruleDisableGrowth get() = cfg.getBoolean("bunker.rules.disableGrowth", true)
    val ruleDisableFluidSource get() = cfg.getBoolean("bunker.rules.disableFluidSource", true)
    val ruleDisableHoppers get() = cfg.getBoolean("bunker.rules.disableHoppers", true)
    val ruleAllowedBonemealGrowth get() = cfg.getBoolean("bunker.rules.allowedBonemealGrowth", true)
    val ruleInitialHorizontalLimit get() = cfg.getInt("bunker.rules.initialHorizontalLimit", 9)
    val ruleHorizontalPlaneOnly get() = cfg.getBoolean("bunker.rules.horizontalPlaneOnly", true)

    // Raid settings
    val raidMinPlayers: Int get() = cfg.getInt("raid.minPlayers", 1)
    val raidMaxConcurrent: Int get() = cfg.getInt("raid.maxConcurrentSessions", 10)
    val raidWorldSizeChunks: Int get() = cfg.getInt("raid.worldSizeChunks", 16)
    val raidDurationMinutes: Int get() = cfg.getInt("raid.durationMinutes", 10)
    val raidExtractionChannelSeconds: Int get() = cfg.getInt("raid.extraction.channelSeconds", 10)
    val raidExtractionRadius: Double get() = cfg.getDouble("raid.extraction.radius", 3.0)
    val raidExtractionParticle: String get() = cfg.getString("raid.extraction.particle", "HAPPY_VILLAGER")!!
    val raidExtractionAvailabilityPercent: Double get() = cfg.getDouble("raid.extraction.availabilityPercent", 0.10)
    val raidQueueAutoStart: Boolean get() = cfg.getBoolean("raid.queue.autoStart", true)
    val raidSpawnSafeScanMaxTries: Int get() = cfg.getInt("raid.spawn.safeScanMaxTries", 50)
    val raidCleanupDeleteWorldOnEnd: Boolean get() = cfg.getBoolean("raid.cleanup.deleteWorldOnEnd", true)
    val raidCleanupDelaySeconds: Int get() = cfg.getInt("raid.cleanup.delaySeconds", 5)
    val raidBossBarTitle: String get() = cfg.getString("raid.bossBar.title", "&aRaid Time Remaining")!!.replace('&', '§')
    val raidBossBarColor: String get() = cfg.getString("raid.bossBar.color", "GREEN")!!
    val raidBossBarStyle: String get() = cfg.getString("raid.bossBar.style", "SOLID")!!

    // Raid preloading settings
    val raidPreloadSpawnRadiusChunks: Int get() = cfg.getInt("raid.preload.spawnRadiusChunks", 0)
    val raidPreloadExtractionRadiusChunks: Int get() = cfg.getInt("raid.preload.extractionRadiusChunks", 0)

    // Communications toggles
    val commRaidBossBar: Boolean get() = cfg.getBoolean("communications.raid.bossBar", true)
    val commRaidExtractingActionBar: Boolean get() = cfg.getBoolean("communications.raid.extractingProgressActionBar", false)
    val commRaidExtractionActiveChat: Boolean get() = cfg.getBoolean("communications.raid.extractionActiveChat", true)
    val commRaidFinalPhaseChat: Boolean get() = cfg.getBoolean("communications.raid.finalPhaseChat", true)
    val commBunkerExpansionSuccessChat: Boolean get() = cfg.getBoolean("communications.bunker.expansionSuccessChat", true)
    val commBunkerExpansionFailChat: Boolean get() = cfg.getBoolean("communications.bunker.expansionFailChat", true)

    // World optimization
    val worldDisableAutosave: Boolean get() = cfg.getBoolean("world.optimize.disableAutosave", true)
    fun worldGamerules(): Map<String, Any?> {
        val sec = cfg.getConfigurationSection("world.optimize.gamerules") ?: return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        for (k in sec.getKeys(false)) {
            map[k] = cfg.get("world.optimize.gamerules.$k")
        }
        return map
    }

    // Starter kit
    val starterKitEnabled: Boolean get() = cfg.getBoolean("starterKit.enabled", true)
    val starterKitOverwriteArmor: Boolean get() = cfg.getBoolean("starterKit.overwriteArmor", false)
    fun starterKitItems(): List<Map<String, Any?>> = cfg.getMapList("starterKit.items").map { raw ->
        @Suppress("UNCHECKED_CAST")
        (raw as Map<Any?, Any?>).entries.associate { (k,v) -> k.toString() to v }
    }

    // Messages generic accessor
    fun message(path: String, def: String): String = cfg.getString(path, def) ?: def

    // New raid queue settings
    val raidQueueCountdownSeconds: Int get() = cfg.getInt("raid.queue.countdownSeconds", 10)
    val raidExtractionOpenAfterSeconds: Int get() = cfg.getInt("raid.extraction.openAfterSeconds", -1)

    // Spawn safety tuning
    val raidSpawnSafetyRadialSamples: Int get() = cfg.getInt("raid.spawnSafety.radialSamples", 80)
    val raidSpawnSafetySquareExtraRadius: Int get() = cfg.getInt("raid.spawnSafety.squareExtraRadius", 32)
    val raidSpawnSafetyUpwardAdjustMax: Int get() = cfg.getInt("raid.spawnSafety.upwardAdjustMax", 6)
    val raidSpawnSafetyDownwardAdjustMax: Int get() = cfg.getInt("raid.spawnSafety.downwardAdjustMax", 12)
    val raidSpawnSafetyEnableDownward: Boolean get() = cfg.getBoolean("raid.spawnSafety.enableDownwardAdjust", true)
    val raidSpawnSafetyEnableRadial: Boolean get() = cfg.getBoolean("raid.spawnSafety.enableRadial", true)
    val raidSpawnSafetyEnableSquare: Boolean get() = cfg.getBoolean("raid.spawnSafety.enableSquareFallback", true)
    val raidSpawnSafetyLogThresholdMs: Long get() = cfg.getLong("raid.spawnSafety.logThresholdMs", 25)

    // Raid template loading
    fun raidTemplates(): List<org.kami.exfilCraft.raid.RaidTemplate> {
        cachedRaidTemplates?.let { return it }
        val sec = cfg.getConfigurationSection("raid.templates")
        val list = mutableListOf<org.kami.exfilCraft.raid.RaidTemplate>()
        if (sec != null) {
            var ordinal = 0
            for (id in sec.getKeys(false)) {
                val base = "raid.templates.$id"
                val enabled = cfg.getBoolean("$base.enabled", true)
                val name = cfg.getString("$base.name", id)!!
                val envStr = cfg.getString("$base.environment", "OVERWORLD")!!.uppercase()
                val env = when(envStr) { "NETHER" -> org.bukkit.World.Environment.NETHER; "THE_END","END" -> org.bukkit.World.Environment.THE_END; else -> org.bukkit.World.Environment.NORMAL }
                val size = cfg.getInt("$base.sizeChunks", 16)
                val durMin = cfg.getInt("$base.durationMinutes", 10)
                val openAfter = cfg.getInt("$base.extractionOpenAfterSeconds", -1).let { if (it < 0) null else it }
                val unlockCond = cfg.getString("$base.unlockCondition", if (openAfter==null) "DRAGON_DEFEATED" else "TIME")!!.uppercase()
                val cond = runCatching { org.kami.exfilCraft.raid.ExtractionUnlockCondition.valueOf(unlockCond) }.getOrElse { org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME }
                val minP = cfg.getInt("$base.minPlayers", 1)
                val maxP = cfg.getInt("$base.maxPlayers", 16)
                val minTeams = cfg.getInt("$base.minTeams", 1)
                val maxTeams = cfg.getInt("$base.maxTeams", Int.MAX_VALUE)
                val maxPlayersPerTeam = cfg.getInt("$base.maxPlayersPerTeam", Int.MAX_VALUE)
                val extractionRadiusOverride = cfg.getDouble("$base.extractionRadius", -1.0).let { if (it>0) it else null }
                val channelSecondsOverride = cfg.getInt("$base.channelSeconds", -1).let { if (it>0) it else null }
                val spawnProtOverride = cfg.getInt("$base.spawnProtectionSeconds", -1).let { if (it>0) it else null }
                val forceNight = cfg.getBoolean("$base.forceNight", false)
                val requireCenterStructure = cfg.getBoolean("$base.requireCenterStructure", false)
                val requireDragon = cfg.getBoolean("$base.requireDragon", false)
                val alwaysEndCities = cfg.getBoolean("$base.alwaysEndCities", false)
                val structures = cfg.getStringList("$base.requiredStructures")
                val centerStructures = cfg.getStringList("$base.centerRequiredStructures")
                val centerRadiusChunks = cfg.getInt("$base.centerRadiusChunks", 4)
                val maxWorldGenAttempts = cfg.getInt("$base.maxWorldGenAttempts", 6)
                val iconMatStrRaw = cfg.getString("$base.iconMaterial", "")!!.trim()
                val displayItemStrRaw = cfg.getString("$base.displayItem", "")!!.trim()
                fun normalizeMatName(input: String): String = input.lowercase().replace("[ -]+".toRegex(), "_").replace(":", "").uppercase()
                val iconMat = iconMatStrRaw.takeIf { it.isNotEmpty() }?.let { matName ->
                    val norm = normalizeMatName(matName)
                    runCatching { org.bukkit.Material.valueOf(norm) }.getOrNull()
                }
                val displayItem = displayItemStrRaw.takeIf { it.isNotEmpty() }?.let { matName ->
                    val norm = normalizeMatName(matName)
                    // special synonyms
                    val synonyms = mapOf(
                        "COBBLE_BLOCK" to "COBBLESTONE",
                        "WITHER_SKULL" to "WITHER_SKELETON_SKULL",
                        "PUR_PUR_BLOCK" to "PURPUR_BLOCK"
                    )
                    val key = synonyms[norm] ?: norm
                    runCatching { org.bukkit.Material.valueOf(key) }.getOrNull()
                }
                val orderCfg = cfg.getInt("$base.order", Int.MIN_VALUE)
                val order = if (orderCfg == Int.MIN_VALUE) ordinal++ else orderCfg
                val spacerAfter = cfg.getBoolean("$base.spacerAfter", false)
                list += org.kami.exfilCraft.raid.RaidTemplate(
                    id, name, env, size, durMin, openAfter, cond, minP, maxP, minTeams, maxTeams, maxPlayersPerTeam,
                    extractionRadiusOverride, channelSecondsOverride, spawnProtOverride, forceNight, requireCenterStructure,
                    structures, centerStructures, centerRadiusChunks, requireDragon, alwaysEndCities, maxWorldGenAttempts, enabled,
                    iconMaterial = iconMat, spacerAfter = spacerAfter, displayItem = displayItem, order = order
                )
            }
        }
        if (list.isEmpty()) {
            // Provide ordered defaults with displayItem & order
            var ord = 0
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "over_small_easy", displayName = "Overworld Small / Easy", environment = org.bukkit.World.Environment.NORMAL,
                sizeChunks = 16, durationMinutes = 10, extractionOpenAfterSeconds = 300, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 2, maxPlayers = 8, minTeams = 2, maxTeams = 4, maxPlayersPerTeam = 4, iconMaterial = org.bukkit.Material.GRASS_BLOCK,
                displayItem = org.bukkit.Material.GRASS_BLOCK, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "over_medium_struct", displayName = "Overworld Medium / Structures", environment = org.bukkit.World.Environment.NORMAL,
                sizeChunks = 32, durationMinutes = 25, extractionOpenAfterSeconds = 1200, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 4, maxPlayers = 32, minTeams = 4, maxTeams = 8, maxPlayersPerTeam = 8, forceNight = true, requireCenterStructure = true,
                centerRequiredStructures = listOf("VILLAGE","PILLAGER_OUTPOST"), centerRadiusChunks = 6, iconMaterial = org.bukkit.Material.COBBLESTONE,
                displayItem = org.bukkit.Material.COBBLESTONE, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "over_big_hard", displayName = "Overworld Large / Hard", environment = org.bukkit.World.Environment.NORMAL,
                sizeChunks = 64, durationMinutes = 45, extractionOpenAfterSeconds = 1500, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 8, maxPlayers = 64, minTeams = 8, maxTeams = 16, maxPlayersPerTeam = 8, requireCenterStructure = true,
                centerRequiredStructures = listOf("MANSION","ANCIENT_CITY"), requiredStructures = listOf("ANCIENT_CITY","STRONGHOLD"), centerRadiusChunks = 8, iconMaterial = org.bukkit.Material.STRUCTURE_BLOCK,
                displayItem = org.bukkit.Material.STRUCTURE_BLOCK, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "nether_small_medium", displayName = "Nether Small / Medium", environment = org.bukkit.World.Environment.NETHER,
                sizeChunks = 16, durationMinutes = 15, extractionOpenAfterSeconds = 600, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 2, maxPlayers = 16, minTeams = 2, maxTeams = 4, maxPlayersPerTeam = 4, iconMaterial = org.bukkit.Material.NETHERRACK,
                displayItem = org.bukkit.Material.NETHERRACK, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "nether_medium_hard", displayName = "Nether Medium / Hard (Center Fortress/Bastion)", environment = org.bukkit.World.Environment.NETHER,
                sizeChunks = 32, durationMinutes = 35, extractionOpenAfterSeconds = 1500, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 4, maxPlayers = 32, minTeams = 4, maxTeams = 8, maxPlayersPerTeam = 8, requireCenterStructure = true,
                centerRequiredStructures = listOf("FORTRESS","BASTION_REMNANT"), centerRadiusChunks = 6, iconMaterial = org.bukkit.Material.BEACON,
                displayItem = org.bukkit.Material.BEACON, order = ord++, spacerAfter = true
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "end_small_pvp", displayName = "End Small / PvP", environment = org.bukkit.World.Environment.THE_END,
                sizeChunks = 16, durationMinutes = 20, extractionOpenAfterSeconds = 900, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 2, maxPlayers = 16, minTeams = 2, maxTeams = 4, maxPlayersPerTeam = 4, iconMaterial = org.bukkit.Material.END_STONE,
                displayItem = org.bukkit.Material.END_STONE, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "end_medium_dragon", displayName = "End Medium / Dragon", environment = org.bukkit.World.Environment.THE_END,
                sizeChunks = 32, durationMinutes = 30, extractionOpenAfterSeconds = null, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.DRAGON_DEFEATED,
                minPlayers = 2, maxPlayers = 16, minTeams = 2, maxTeams = 4, maxPlayersPerTeam = 4, requireDragon = true, iconMaterial = org.bukkit.Material.DRAGON_HEAD,
                displayItem = org.bukkit.Material.DRAGON_HEAD, order = ord++
            )
            list += org.kami.exfilCraft.raid.RaidTemplate(
                id = "end_big_hard", displayName = "End Large / Hard", environment = org.bukkit.World.Environment.THE_END,
                sizeChunks = 64, durationMinutes = 60, extractionOpenAfterSeconds = 1800, unlockCondition = org.kami.exfilCraft.raid.ExtractionUnlockCondition.TIME,
                minPlayers = 8, maxPlayers = 64, minTeams = 8, maxTeams = 16, maxPlayersPerTeam = 8, alwaysEndCities = true, iconMaterial = org.bukkit.Material.PURPUR_BLOCK,
                displayItem = org.bukkit.Material.PURPUR_BLOCK, order = ord++
            )
        }
        val result = list.filter { it.enabled }.sortedBy { it.order }
        cachedRaidTemplates = result
        return result
    }
}
