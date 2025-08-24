package org.kami.exfilCraft.command.tab

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class RaidTab : TabCompleter {
    private val root = listOf("start","status","queue")
    private val queueSubs = listOf("leave","status","available")
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> root.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("queue", true)) queueSubs.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            else -> emptyList()
        }
    }
}
