package org.kami.exfilCraft.command.tab

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.kami.exfilCraft.bunker.BunkerService

class BunkerTab(private val bunkers: BunkerService) : TabCompleter {
    private val root = listOf("info","warp","extend","invite","accept")
    private val dirs = listOf("north","south","east","west","n","s","e","w")
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return root.filter { it.startsWith(args[0], true) }
        if (args.size == 2 && args[0].equals("extend", true)) return dirs.filter { it.startsWith(args[1], true) }
        return emptyList()
    }
}

