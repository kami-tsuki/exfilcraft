package org.kami.exfilCraft.world

import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.logging.LoggingService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

class WorldManagerService(private val plugin: JavaPlugin, private val config: ConfigService, logging: LoggingService) {
    private val log = logging.section("WORLD")
    private val raidWorlds = ConcurrentHashMap<Long, World>()

    fun createRaidWorld(id: Long): World? {
        val name = "exfil_raid_${System.currentTimeMillis()}_${id}_${ThreadLocalRandom.current().nextInt(1000,9999)}"
        val creator = WorldCreator(name)
        creator.environment(World.Environment.NORMAL)
        creator.type(WorldType.NORMAL)
        creator.seed(ThreadLocalRandom.current().nextLong())
        val world = creator.createWorld() ?: return null
        applyOptimizations(world)
        raidWorlds[id] = world
        return world
    }

    private fun applyOptimizations(world: World) {
        if (config.worldDisableAutosave) world.isAutoSave = false
        world.difficulty = Difficulty.HARD
        config.worldGamerules().forEach { (k,v) ->
            try {
                @Suppress("UNCHECKED_CAST")
                val gr = GameRule.getByName(k) as GameRule<Any>?
                if (gr != null && v != null) world.setGameRule(gr, v)
            } catch (t: Throwable) {
                log.warn("GameRule apply failed", "rule" to k, "value" to v, "error" to (t.message ?: "?"))
            }
        }
    }

    fun unloadAll() {
        raidWorlds.values.forEach { w ->
            Bukkit.unloadWorld(w, false)
        }
        raidWorlds.clear()
    }
}
