/* MIT License 2025 tsuki */
package org.kami.exfilCraft.command

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.profile.ProfileService

class BunkerCommand(
    private val plugin: org.bukkit.plugin.Plugin,
    private val bunkers: BunkerService,
    private val profiles: ProfileService,
    private val config: ConfigService
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("${ChatColor.RED}Player only"); return true }
        val sub = args.firstOrNull()?.lowercase() ?: "info"
        return when (sub) {
            "info" -> handleInfo(sender)
            "warp" -> handleWarp(sender)
            "extend" -> handleExtend(sender, args)
            "invite" -> handleInvite(sender, args)
            "accept" -> handleAccept(sender, args)
            else -> {
                sender.sendMessage("${ChatColor.RED}Unknown subcommand.")
                true
            }
        }
    }

    private fun handleInvite(player: Player, args: Array<out String>): Boolean {
        val targetName = args.getOrNull(1) ?: run { player.sendMessage("${ChatColor.RED}Usage: /bunker invite <player>"); return true }
        val target = plugin.server.getPlayerExact(targetName) ?: run { player.sendMessage("${ChatColor.RED}Player not online"); return true }
        if (target.uniqueId == player.uniqueId) { player.sendMessage("${ChatColor.RED}Cannot invite yourself."); return true }
        val ok = bunkers.inviteMember(player.uniqueId, target.uniqueId)
        if (ok) {
            player.sendMessage("${ChatColor.GREEN}Invite sent to ${target.name}. Expires in 60m.")
            target.sendMessage("${ChatColor.AQUA}${player.name} invited you to their bunker. Use /bunker accept ${player.name} to join.")
        } else player.sendMessage("${ChatColor.RED}Invite failed (capacity, already in bunker, or no bunker).")
        return true
    }

    private fun handleAccept(player: Player, args: Array<out String>): Boolean {
        val inviterName = args.getOrNull(1) ?: run { player.sendMessage("${ChatColor.RED}Usage: /bunker accept <player>"); return true }
        val inviter = plugin.server.getPlayerExact(inviterName) ?: run { player.sendMessage("${ChatColor.RED}Inviter not online (still required for now)"); return true }
        val ok = bunkers.acceptInvite(player.uniqueId, inviter.uniqueId)
        if (ok) {
            player.sendMessage("${ChatColor.GREEN}Joined bunker of ${inviter.name}.")
            inviter.sendMessage("${ChatColor.AQUA}${player.name} accepted your bunker invite.")
        } else player.sendMessage("${ChatColor.RED}Accept failed (no invite, expired, capacity reached, or you already belong to a bunker).")
        return true
    }

    private fun handleInfo(player: Player): Boolean {
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId)
            ?: run {
                player.sendMessage("${ChatColor.YELLOW}Allocating bunker...")
                val b = bunkers.allocateIfAbsent(player)
                bunkers.teleportToBunker(player, b)
                player.sendMessage("${ChatColor.GREEN}Bunker created.")
                return true
            }
        val cooldown = bunkers.getExpansionCooldownRemainingSeconds(bunker)
        val nextCost = bunkers.nextExpansionCostXp(bunker)
        val members = bunkers.getMembers(bunker).mapNotNull { plugin.server.getPlayer(it)?.name ?: plugin.server.getOfflinePlayer(it).name ?: it.toString().substring(0,8) }
        player.sendMessage("${ChatColor.AQUA}Bunker Info:")
        player.sendMessage("${ChatColor.GRAY}Origin Chunk: ${bunker.originChunkX}, ${bunker.originChunkZ}")
        player.sendMessage("${ChatColor.GRAY}Cubes: ${bunker.cubesCount}")
        player.sendMessage("${ChatColor.GRAY}Cube Size: ${bunker.cubeSize}")
        player.sendMessage("${ChatColor.GRAY}Members (${members.size}/4): ${members.joinToString(", ")}")
        player.sendMessage("${ChatColor.GRAY}Next Expansion Cost: ${nextCost} XP")
        player.sendMessage("${ChatColor.GRAY}Expansion Cooldown: ${cooldown}s")
        return true
    }

    private fun handleWarp(player: Player): Boolean {
        val primaryWorldName = player.server.worlds.firstOrNull()?.name
        if (primaryWorldName == null) {
            player.sendMessage("${ChatColor.RED}Primary world unavailable.")
            return true
        }
        // Only allow warp if in primary world and not already in bunker world
        if (player.world.name != primaryWorldName) {
            player.sendMessage("${ChatColor.RED}You can only use /bunker warp from the main world.")
            return true
        }
        if (player.world.name == config.bunkerWorldName) {
            player.sendMessage("${ChatColor.RED}You are already in the bunker world.")
            return true
        }
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId) ?: bunkers.allocateIfAbsent(player)
        bunkers.teleportToBunker(player, bunker)
        player.sendMessage("${ChatColor.GREEN}Warped to your bunker.")
        return true
    }

    private fun handleExtend(player: Player, args: Array<out String>): Boolean {
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId)
            ?: run {
                player.sendMessage("${ChatColor.YELLOW}No bunker found, allocating...")
                bunkers.allocateIfAbsent(player)
            }
        val b = bunkers.getBunkerForPlayer(player.uniqueId) ?: return true

        val dirToken = args.getOrNull(1)
        val explicit = dirToken?.let { bunkers.parseDirectionToken(it) }
        if (dirToken != null && explicit == null) {
            player.sendMessage("${ChatColor.RED}Unknown direction '${dirToken}'. Use north|south|east|west (or n/s/e/w)")
            return true
        }
        if (!bunkers.extendBunkerFacing(player, b, explicit)) {
            // On failure due to limit or duplicate, optionally list available
            val cur = bunkers.getCubeContaining(b, player.location.blockX, player.location.blockY, player.location.blockZ) ?: Triple(0,0,0)
            val avail = bunkers.availableAdjacentDirections(b, cur.first, cur.third)
            if (avail.isNotEmpty()) {
                val names = avail.map { bunkers.directionName(it) }.joinToString(", ")
                player.sendMessage("${ChatColor.GRAY}Available: $names")
            }
        }
        return true
    }
}
