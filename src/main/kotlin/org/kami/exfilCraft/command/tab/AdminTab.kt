package org.kami.exfilCraft.command.tab

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class AdminTab : TabCompleter {
    private val root = listOf("bunker","raid","give")
    private val bunkerSubs = listOf("reset","realloc")
    private val raidSubs = listOf("forcestart","forceend","forceextract","unlock","prune","sessions")
    private val giveSubs = listOf("compass")

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when(args.size) {
            1 -> root.filter { it.startsWith(args[0], true) }
            2 -> when(args[0].lowercase()) {
                "bunker" -> bunkerSubs.filter { it.startsWith(args[1], true) }
                "raid" -> raidSubs.filter { it.startsWith(args[1], true) }
                "give" -> giveSubs.filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when(args[0].lowercase()) {
                "bunker" -> if (args[1].equals("reset", true) || args[1].equals("realloc", true)) onlinePlayers(args[2]) else emptyList()
                "raid" -> when(args[1].lowercase()) {
                    "forceend" -> onlinePlayers(args[2]) + listOf("all").filter { it.startsWith(args[2], true) }
                    "forceextract" -> onlinePlayers(args[2])
                    "forcestart" -> templateIds().filter { it.startsWith(args[2], true) }
                    "unlock" -> (templateIds() + onlinePlayers(args[2])).filter { it.startsWith(args[2], true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            4 -> when(args[0].lowercase()) {
                "raid" -> if (args[1].equals("forceextract", true)) listOf("alive").filter { it.startsWith(args[3], true) } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun onlinePlayers(prefix: String): List<String> = Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, true) }
    private fun templateIds(): List<String> = org.bukkit.Bukkit.getPluginManager().getPlugin("ExfilCraft")?.let { plugin ->
        val field = plugin.javaClass.getDeclaredField("services"); field.isAccessible = true
        val reg = field.get(plugin) as? org.kami.exfilCraft.core.ServiceRegistry
        val cfg = reg?.getOptional<org.kami.exfilCraft.core.ConfigService>() ?: return emptyList()
        cfg.raidTemplates().map { it.id }
    } ?: emptyList()
}
