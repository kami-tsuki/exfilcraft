package org.kami.exfilCraft.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.enchantment.PrepareItemEnchantEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.ItemStack
import org.kami.exfilCraft.raid.RaidService
import org.kami.exfilCraft.command.AdminCommand

class PortalAndItemListener(private val raids: RaidService): Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPortal(e: PlayerTeleportEvent) {
        if (e.cause == TeleportCause.END_PORTAL) {
            if (raids.portalExtract(e.player)) {
                e.isCancelled = true
            }
        }
    }

    private fun isOstk(item: ItemStack?): Boolean = AdminCommand.isOstk(item)

    @EventHandler(ignoreCancelled = true)
    fun onEnchantPrepare(e: PrepareItemEnchantEvent) {
        if (isOstk(e.item)) e.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true)
    fun onEnchant(e: EnchantItemEvent) { if (isOstk(e.item)) e.isCancelled = true }
    @EventHandler(ignoreCancelled = true)
    fun onAnvil(e: PrepareAnvilEvent) {
        val first = e.inventory.getItem(0)
        val second = e.inventory.getItem(1)
        if (isOstk(first) || isOstk(second)) {
            e.result = null
        }
    }
    @EventHandler(ignoreCancelled = true)
    fun onAnvilClick(e: InventoryClickEvent) {
        val inv = e.inventory
        if (inv.type.name.contains("ANVIL")) {
            val first = inv.getItem(0)
            val second = inv.getItem(1)
            if (isOstk(first) || isOstk(second)) {
                // Block taking enchanted/modified OSTK from result slot
                if (e.slot == 2) e.isCancelled = true
            }
        }
    }
    @EventHandler(ignoreCancelled = true)
    fun onDamageItem(e: PlayerItemDamageEvent) {
        val item = e.item
        if (!isOstk(item)) return
        // Allow normal durability reduction; when reaches 0 it breaks naturally. Prevent Unbreaking style reductions.
        // Force damage exactly 1 per swing.
        e.damage = 1
    }
}

