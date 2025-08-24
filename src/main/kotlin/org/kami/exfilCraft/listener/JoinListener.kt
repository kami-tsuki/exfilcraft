/* MIT License 2025 tsuki */
package org.kami.exfilCraft.listener

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.profile.ProfileService
import org.kami.exfilCraft.raid.RaidService
import org.kami.exfilCraft.raid.RaidState

class JoinListener(
    private val profiles: ProfileService,
    private val bunkers: BunkerService,
    private val config: ConfigService,
    private val raids: RaidService? = null
) : Listener {

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        val profile = profiles.getOrCreate(p.uniqueId)
        val raidSession = raids?.getSessionFor(p.uniqueId)
        if (raidSession != null) {
            val now = System.currentTimeMillis() / 1000L
            val dc = raidSession.disconnectInfo[p.uniqueId]
            if (raidSession.state == RaidState.ACTIVE && dc != null) {
                val absence = now - dc.leftAt
                val threshold = (raidSession.durationSeconds * 0.10).toLong()
                if (absence < threshold && !dc.forfeited) {
                    // Rejoin raid at stored location (slightly above to avoid suffocation)
                    val loc = dc.loc.clone().add(0.0, 0.5, 0.0)
                    if (loc.world != null) {
                        p.teleport(loc)
                        raids.giveCompassForReconnect(p, raidSession)
                        p.sendMessage("${ChatColor.AQUA}You reconnected to an active raid. (${absence}s absent)")
                        return
                    }
                } else {
                    // Forfeit: drop inventory at stored location if world still exists
                    val world = dc.loc.world
                    if (world != null && !dc.forfeited) {
                        dropAll(world, dc.loc, p)
                        dc.forfeited = true
                    }
                    p.inventory.clear()
                    p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
                    p.sendMessage("${ChatColor.RED}You forfeited raid loot due to disconnect (${absence}s) or raid ended.")
                }
            }
        }
        // Ensure bunker exists
        val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: bunkers.allocateIfAbsent(p)
        bunkers.teleportToBunker(p, bunker)
        if (!profile.starterGiven) {
            giveStarterItems(p)
            profiles.markStarterGiven(p.uniqueId)
            p.sendMessage("${ChatColor.GREEN}Starter kit granted.")
        }
    }

    private fun dropAll(world: org.bukkit.World, loc: org.bukkit.Location, p: org.bukkit.entity.Player) {
        val inv = p.inventory
        (inv.contents + inv.armorContents + arrayOf(inv.itemInOffHand)).filterNotNull().forEach { item ->
            if (item.type != Material.AIR) world.dropItemNaturally(loc, item.clone())
        }
    }

    private fun giveStarterItems(p: org.bukkit.entity.Player) {
        val inv = p.inventory
        // Armor
        if (inv.helmet == null) inv.helmet = ItemStack(Material.LEATHER_HELMET)
        if (inv.chestplate == null) inv.chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        if (inv.leggings == null) inv.leggings = ItemStack(Material.LEATHER_LEGGINGS)
        if (inv.boots == null) inv.boots = ItemStack(Material.LEATHER_BOOTS)
        // Tools / weapons
        inv.addItem(ItemStack(Material.WOODEN_PICKAXE))
        inv.addItem(ItemStack(Material.STONE_SWORD))
        inv.addItem(ItemStack(Material.BOW))
        inv.addItem(ItemStack(Material.ARROW, 16))
        // Offhand torches
        if (inv.itemInOffHand.type == Material.AIR) {
            inv.setItemInOffHand(ItemStack(Material.TORCH, 16))
        } else {
            inv.addItem(ItemStack(Material.TORCH, 16))
        }
    }
}
