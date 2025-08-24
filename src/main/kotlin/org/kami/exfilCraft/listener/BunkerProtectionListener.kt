/* MIT License 2025 tsuki */
package org.kami.exfilCraft.listener

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.core.debug
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent

class BunkerProtectionListener(
    private val plugin: JavaPlugin,
    private val config: ConfigService,
    private val bunkers: BunkerService
) : Listener {

    private fun isBunkerWorld(world: World) = world.name == config.bunkerWorldName

    private fun playerOwnsLocation(player: Player, x: Int, y: Int, z: Int): Boolean {
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId) ?: return false
        return bunkers.isInsideAnyCube(bunker, x, y, z)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        if (!isBunkerWorld(e.block.world)) return
        val p = e.player
        val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: run { e.isCancelled = true; return }
        val inside = bunkers.isInsideAnyCube(bunker, e.block.x, e.block.y, e.block.z)
        val floor = e.block.y == bunker.startY
        val belowFloor = e.block.y < bunker.startY
        if (!inside || belowFloor || floor) {
            e.isCancelled = true
            p.sendMessage("§cNot allowed outside your bunker${if (floor) " (floor protected)" else ""}")
            debug(plugin, config.debugEnabled) { "CANCEL break @${e.block.x},${e.block.y},${e.block.z} inside=$inside floor=$floor belowFloor=$belowFloor" }
        } else {
            debug(plugin, config.debugEnabled) { "ALLOW break @${e.block.x},${e.block.y},${e.block.z}" }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        val b = e.blockPlaced
        if (!isBunkerWorld(b.world)) return
        val p = e.player
        val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: run { e.isCancelled = true; return }
        val inside = bunkers.isInsideAnyCube(bunker, b.x, b.y, b.z)
        val belowFloor = b.y < bunker.startY
        if (!inside || belowFloor) {
            e.isCancelled = true
            p.sendMessage("§cNot allowed outside your bunker")
            debug(plugin, config.debugEnabled) { "CANCEL place @${b.x},${b.y},${b.z} inside=$inside belowFloor=$belowFloor" }
        } else {
            debug(plugin, config.debugEnabled) { "ALLOW place @${b.x},${b.y},${b.z}" }
        }
    }

    // Growth & environmental events
    @EventHandler(ignoreCancelled = true)
    fun onBlockGrow(e: BlockGrowEvent) { if (isBunkerWorld(e.block.world) && config.ruleDisableGrowth) e.isCancelled = true }
    @EventHandler(ignoreCancelled = true)
    fun onStructureGrow(e: StructureGrowEvent) { if (isBunkerWorld(e.world) && config.ruleDisableGrowth) e.isCancelled = true }
    @EventHandler(ignoreCancelled = true)
    fun onBlockSpread(e: BlockSpreadEvent) { if (isBunkerWorld(e.block.world) && config.ruleDisableGrowth) e.isCancelled = true }
    @EventHandler(ignoreCancelled = true)
    fun onLeaves(e: LeavesDecayEvent) { if (isBunkerWorld(e.block.world) && config.ruleDisableGrowth) e.isCancelled = true }
    @EventHandler(ignoreCancelled = true)
    fun onFertilize(e: BlockFertilizeEvent) {
        if (!isBunkerWorld(e.block.world)) return
        if (config.ruleDisableGrowth && !config.ruleAllowedBonemealGrowth) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFromTo(e: BlockFromToEvent) {
        if (!isBunkerWorld(e.block.world)) return
        if (config.ruleDisableFluidSource) {
            val to = e.toBlock
            if (to.type == Material.AIR) return
            e.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryMove(e: InventoryMoveItemEvent) {
        if (!config.ruleDisableHoppers) return
        val src = e.source.location?.world?.name
        val dst = e.destination.location?.world?.name
        if (src == config.bunkerWorldName || dst == config.bunkerWorldName) {
            val holderType = (e.source.holder?.javaClass?.simpleName ?: "") + (e.destination.holder?.javaClass?.simpleName ?: "")
            if (holderType.contains("Hopper", ignoreCase = true)) e.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCreatureSpawn(e: CreatureSpawnEvent) {
        val w = e.location.world ?: return
        if (isBunkerWorld(w) && config.ruleDisableMobSpawns) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(e: EntityDamageEvent) {
        if (!config.rulePreventDamage) return
        if (e.entity.world.name == config.bunkerWorldName) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFood(e: FoodLevelChangeEvent) {
        if (!config.rulePreventHunger) return
        if (e.entity.world.name != config.bunkerWorldName) return
        val player = e.entity as? Player ?: return
        val newLevel = e.foodLevel
        val current = player.foodLevel
        // Cancel only hunger decreases; allow increases from eating.
        if (newLevel < current) {
            e.isCancelled = true
            // Maintain max saturation for natural regen
            player.saturation = 20f
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(e: BlockPistonExtendEvent) {
        if (!isBunkerWorld(e.block.world)) return
        e.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(e: BlockPistonRetractEvent) {
        if (!isBunkerWorld(e.block.world)) return
        e.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(e: EntityExplodeEvent) { val w = e.location.world ?: return; if (isBunkerWorld(w)) { e.blockList().clear(); e.isCancelled = true } }
    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) { if (isBunkerWorld(e.block.world)) { e.blockList().clear(); e.isCancelled = true } }
    @EventHandler(ignoreCancelled = true)
    fun onBucketEmpty(e: PlayerBucketEmptyEvent) {
        if (!isBunkerWorld(e.block.world)) return
        val bunker = bunkers.getBunkerForPlayer(e.player.uniqueId) ?: run { e.isCancelled = true; return }
        if (!bunkers.isInsideAnyCube(bunker, e.block.x, e.block.y, e.block.z)) e.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(e: PlayerBucketFillEvent) {
        if (!isBunkerWorld(e.block.world)) return
        val bunker = bunkers.getBunkerForPlayer(e.player.uniqueId) ?: run { e.isCancelled = true; return }
        if (!bunkers.isInsideAnyCube(bunker, e.block.x, e.block.y, e.block.z)) e.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEvent) {
        val b = e.clickedBlock ?: return
        if (!isBunkerWorld(b.world)) return
        val bunker = bunkers.getBunkerForPlayer(e.player.uniqueId) ?: run { e.isCancelled = true; return }
        if (!bunkers.isInsideAnyCube(bunker, b.x, b.y, b.z)) e.isCancelled = true
    }
}
