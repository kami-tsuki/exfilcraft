package org.kami.exfilCraft.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.kami.exfilCraft.raid.RaidService

class RaidListener(private val raids: RaidService): Listener {
    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        raids.handlePlayerDeath(e.entity)
    }
    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        raids.handlePlayerQuit(e.player)
    }
    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        raids.handleRespawn(e.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(e: PlayerMoveEvent) {
        if (e.from.x == e.to?.x && e.from.y == e.to?.y && e.from.z == e.to?.z) return
        val uuid = e.player.uniqueId
        if (raids.isUnderSpawnProtection(uuid)) {
            e.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(e: EntityDamageEvent) {
        val p = e.entity as? org.bukkit.entity.Player ?: return
        if (raids.isUnderSpawnProtection(p.uniqueId)) e.isCancelled = true
    }
}
