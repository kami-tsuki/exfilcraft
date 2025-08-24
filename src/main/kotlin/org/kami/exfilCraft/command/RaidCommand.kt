package org.kami.exfilCraft.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.kami.exfilCraft.ExfilCraft
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.raid.RaidService

class RaidCommand(private val plugin: ExfilCraft, private val raids: RaidService, private val config: ConfigService): CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Only players."); return true }
        if (args.isEmpty()) {
            sender.sendMessage("§eUsage: /$label start | status | queue <leave|status|available>")
            return true
        }
        when(args[0].lowercase()) {
            "start" -> raids.openTemplateMenu(sender)
            "status" -> raids.status(sender)
            "queue" -> {
                val sub = args.getOrNull(1)?.lowercase()
                when(sub) {
                    "leave" -> raids.leaveQueue(sender)
                    "status" -> raids.queueStatus(sender)
                    "available" -> {
                        val avail = raids.availablePlayers()
                        sender.sendMessage("§aAvailable players (${avail.size}): §7${avail.joinToString { it.name }}")
                    }
                    else -> sender.sendMessage("§eUsage: /$label queue <leave|status|available>")
                }
            }
            else -> sender.sendMessage("§cUnknown subcommand")
        }
        return true
    }
}
