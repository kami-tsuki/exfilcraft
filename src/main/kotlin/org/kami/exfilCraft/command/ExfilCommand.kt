/* MIT License 2025 tsuki */
package org.kami.exfilCraft.command

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.kami.exfilCraft.ExfilCraft
import org.kami.exfilCraft.core.ConfigService

class ExfilCommand(private val plugin: ExfilCraft, private val config: ConfigService) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.YELLOW}Usage: /$label version|debug|reload")
            return true
        }
        when (args[0].lowercase()) {
            "version" -> {
                sender.sendMessage("${ChatColor.AQUA}ExfilCraft ${plugin.description.version} by ${plugin.description.authors.joinToString()}")
            }
            "debug" -> {
                if (!sender.hasPermission("exfil.admin")) { sender.sendMessage("${ChatColor.RED}No permission"); return true }
                val new = !config.debugEnabled
                plugin.config.set("debug", new)
                plugin.saveConfig()
                config.load()
                sender.sendMessage("${ChatColor.GREEN}Debug set to $new")
            }
            "reload" -> {
                if (!sender.hasPermission("exfil.admin")) { sender.sendMessage("${ChatColor.RED}No permission"); return true }
                config.reload()
                sender.sendMessage("${ChatColor.GREEN}Config reloaded")
            }
            else -> sender.sendMessage("${ChatColor.RED}Unknown subcommand")
        }
        return true
    }
}
