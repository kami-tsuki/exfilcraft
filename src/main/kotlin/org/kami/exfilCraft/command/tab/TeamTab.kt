package org.kami.exfilCraft.command.tab

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.kami.exfilCraft.team.TeamService

class TeamTab(private val teams: TeamService) : TabCompleter {
    private val root = listOf("create","invite","join","leave","disband","info")
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return root.filter { it.startsWith(args[0], true) }
        return emptyList()
    }
}

