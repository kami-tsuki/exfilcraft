package org.kami.exfilCraft.listener

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.world.PortalCreateEvent
import org.kami.exfilCraft.core.ConfigService

class GlobalRestrictionListener(private val config: ConfigService): Listener {
    private fun isBunker(world: World) = world.name == config.bunkerWorldName
    private fun isRaid(world: World) = world.name.startsWith("exfil_raid_")

    // Ender chest placing blocked in bunker & raid worlds
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        if (e.blockPlaced.type == Material.ENDER_CHEST && (isBunker(e.block.world) || isRaid(e.block.world))) {
            e.isCancelled = true
            e.player.sendMessage("§cEnder chests disabled here.")
        }
    }

    // Ender chest opening + end portal eye insertion + ender pearl use
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEvent) {
        val player = e.player
        val world = player.world
        val item = e.item?.type
        val block = e.clickedBlock
        if (block != null) {
            // Opening ender chest
            if (block.type == Material.ENDER_CHEST && (isBunker(world) || isRaid(world))) {
                e.isCancelled = true
                player.sendMessage("§cEnder chests disabled here.")
                return
            }
            // Prevent inserting eyes into end portal frames
            if (block.type == Material.END_PORTAL_FRAME && item == Material.ENDER_EYE) {
                e.isCancelled = true
                player.sendMessage("§cEnd portals disabled.")
                return
            }
        }
        // Prevent ender pearl throw in bunker
        if (item == Material.ENDER_PEARL && isBunker(world)) {
            e.isCancelled = true
            player.sendMessage("§cEnder pearls disabled in bunker.")
        }
    }

    // Crafting ender chest blocked globally
    @EventHandler(ignoreCancelled = true)
    fun onPrepareCraft(e: PrepareItemCraftEvent) {
        val result = e.inventory.result ?: return
        if (result.type == Material.ENDER_CHEST) {
            e.inventory.result = null
            e.viewers.filterIsInstance<Player>().forEach { it.sendMessage("§cEnder chests cannot be crafted.") }
        }
    }

    // Cancel ender pearl projectile in bunker in case other plugins spawn it
    @EventHandler(ignoreCancelled = true)
    fun onProjectileLaunch(e: ProjectileLaunchEvent) {
        val pearl = e.entity as? EnderPearl ?: return
        val shooter = pearl.shooter as? Player ?: return
        if (isBunker(shooter.world)) {
            e.isCancelled = true
            shooter.sendMessage("§cEnder pearls disabled in bunker.")
        }
    }

    // Fishing disabled in bunker
    @EventHandler(ignoreCancelled = true)
    fun onFish(e: PlayerFishEvent) {
        if (isBunker(e.player.world)) {
            e.isCancelled = true
            e.player.sendMessage("§cFishing disabled in bunker.")
        }
    }

    // Disable any portal creation (nether/end)
    @EventHandler(ignoreCancelled = true)
    fun onPortalCreate(e: PortalCreateEvent) {
        e.isCancelled = true
    }

    // Disable portal teleportation (players)
    @EventHandler(ignoreCancelled = true)
    fun onPlayerPortal(e: PlayerPortalEvent) {
        e.isCancelled = true
        e.player.sendMessage("§cPortals disabled.")
    }

    // Disable portal teleportation (entities)
    @EventHandler(ignoreCancelled = true)
    fun onEntityPortal(e: EntityPortalEvent) {
        e.isCancelled = true
    }
}

