package org.kami.exfilCraft.listener

import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.persistence.PersistentDataType
import org.kami.exfilCraft.raid.RaidService
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class RaidTemplateListener(private val plugin: JavaPlugin, private val raids: RaidService): Listener {
    private val key = NamespacedKey(plugin, "raid_template_id")
    private val menuTitle = "§8Select Raid Template"

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMenuClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        val current = e.currentItem ?: return
        val meta = current.itemMeta ?: return
        val id = meta.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return
        e.isCancelled = true
        p.closeInventory()
        raids.handleTemplateClick(p, id)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDragonDeath(e: EntityDeathEvent) {
        if (e.entity is EnderDragon) {
            val world = e.entity.world
            raids.handleDragonDeath(world)
        }
    }
}
