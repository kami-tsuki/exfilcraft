/* MIT License 2025 tsuki */
package org.kami.exfilCraft.command

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.kami.exfilCraft.team.TeamService
import java.util.*

class TeamCommand(private val teams: TeamService) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("${ChatColor.RED}Player only"); return true }
        val sub = args.firstOrNull()?.lowercase() ?: "info"
        return when (sub) {
            "create" -> {
                if (teams.getTeam(sender.uniqueId) != null) {
                    sender.sendMessage("${ChatColor.RED}Already in a team.")
                } else if (teams.create(sender.uniqueId)) {
                    sender.sendMessage("${ChatColor.GREEN}Team created. You are the leader.")
                } else sender.sendMessage("${ChatColor.RED}Failed to create team.")
                true
            }
            "disband" -> {
                val t = teams.getTeam(sender.uniqueId)
                if (t == null) sender.sendMessage("${ChatColor.RED}Not in a team.")
                else if (t.leader != sender.uniqueId) sender.sendMessage("${ChatColor.RED}Only leader can disband.")
                else if (teams.disband(sender.uniqueId)) sender.sendMessage("${ChatColor.YELLOW}Team disbanded.")
                true
            }
            "leave" -> {
                val t = teams.getTeam(sender.uniqueId)
                if (t == null) sender.sendMessage("${ChatColor.RED}Not in a team.")
                else if (t.leader == sender.uniqueId) sender.sendMessage("${ChatColor.RED}Leader must disband instead.")
                else if (teams.leave(sender.uniqueId)) sender.sendMessage("${ChatColor.YELLOW}You left the team.")
                true
            }
            "invite" -> {
                val targetName = args.getOrNull(1)
                if (targetName == null) { sender.sendMessage("${ChatColor.RED}Usage: /$label invite <player>"); return true }
                val t = teams.getTeam(sender.uniqueId)
                if (t == null || t.leader != sender.uniqueId) { sender.sendMessage("${ChatColor.RED}You must be team leader."); return true }
                val target = sender.server.getPlayerExact(targetName)
                if (target == null) { sender.sendMessage("${ChatColor.RED}Player not found."); return true }
                if (teams.invite(sender.uniqueId, target.uniqueId)) {
                    sender.sendMessage("${ChatColor.GREEN}Invited ${target.name}.")
                    target.sendMessage("${ChatColor.AQUA}Team invite from ${sender.name}. Use /team join ${sender.name}")
                } else sender.sendMessage("${ChatColor.RED}Invite failed.")
                true
            }
            "join" -> {
                val leaderName = args.getOrNull(1)
                if (leaderName == null) { sender.sendMessage("${ChatColor.RED}Usage: /$label join <leader>"); return true }
                val leader = sender.server.getPlayerExact(leaderName)
                if (leader == null) { sender.sendMessage("${ChatColor.RED}Leader offline."); return true }
                if (teams.accept(sender.uniqueId, leader.uniqueId)) {
                    sender.sendMessage("${ChatColor.GREEN}Joined ${leader.name}'s team.")
                    leader.sendMessage("${ChatColor.AQUA}${sender.name} joined the team.")
                } else sender.sendMessage("${ChatColor.RED}Join failed (no invite?).")
                true
            }
            "info" -> {
                val t = teams.getTeam(sender.uniqueId)
                if (t == null) sender.sendMessage("${ChatColor.YELLOW}Not in a team.") else {
                    sender.sendMessage("${ChatColor.AQUA}Team Leader: ${sender.server.getPlayer(t.leader)?.name ?: t.leader}")
                    val members = t.members.map { sender.server.getPlayer(it)?.name ?: it.toString().substring(0,8) }
                    sender.sendMessage("${ChatColor.GRAY}Members (${t.members.size}): ${members.joinToString(", ")}")
                }
                true
            }
            else -> {
                sender.sendMessage("${ChatColor.YELLOW}Usage: /$label create|invite <p>|join <leader>|leave|disband|info")
                true
            }
        }
    }
}

