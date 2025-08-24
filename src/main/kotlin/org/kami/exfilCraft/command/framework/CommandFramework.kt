package org.kami.exfilCraft.command.framework

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import kotlin.math.min

/**
 * Lightweight hierarchical command framework.
 * Supports nested subcommands, automatic help generation, permission + player checks, usage validation & tab completion.
 */
class RootCommand(
    private val name: String,
    private val description: String,
    private val permission: String? = null,
    private val allowConsole: Boolean = true,
    private val headerColor: ChatColor = ChatColor.AQUA,
    private val accent: ChatColor = ChatColor.YELLOW,
    private val error: ChatColor = ChatColor.RED,
    private val maxPerPage: Int = 8
) : CommandExecutor, TabCompleter {

    private val subcommands = mutableListOf<Subcommand>()

    fun register(sub: Subcommand): RootCommand { subcommands += sub; return this }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (permission != null && !sender.hasPermission(permission)) { sender.sendMessage("${error}No permission."); return true }
        if (!allowConsole && sender !is Player) { sender.sendMessage("${error}Player only."); return true }
        if (args.isEmpty() || args[0].equals("help", true)) {
            val target = args.getOrNull(1)
            if (target != null && !target.matches(Regex("\\d+"))) {
                val pathTokens = target.split(":", ".", "/").filter { it.isNotBlank() }
                val path = resolvePath(pathTokens)
                if (path != null) { sendSingleHelp(sender, label, path) } else help(sender, label, 1)
            } else {
                val page = target?.toIntOrNull() ?: 1
                help(sender, label, page)
            }
            return true
        }
        val resolution = resolveExecutable(args.toList(), subcommands)
        if (resolution == null) {
            sender.sendMessage("${error}Unknown subcommand. Use /$label help")
            // Suggest close matches from first token
            if (args.isNotEmpty()) {
                val token = args[0].lowercase()
                val suggestions = allLeafCommands().map { it.path() }.filter { it.startsWith(token) }.take(5)
                if (suggestions.isNotEmpty()) {
                    sender.sendMessage("${ChatColor.GRAY}Did you mean: ${ChatColor.WHITE}${suggestions.joinToString(", ")}")
                }
            }
            return true
        }
        val (sub, consumed) = resolution
        if (sub.permission != null && !sender.hasPermission(sub.permission)) { sender.sendMessage("${error}No permission."); return true }
        if (sub.playerOnly && sender !is Player) { sender.sendMessage("${error}Player only."); return true }
        val remaining = args.drop(consumed)
        if (remaining.size < sub.minArgs || remaining.size > sub.maxArgs) {
            sender.sendMessage("${error}Usage: /$label ${sub.fullUsage()} ")
            return true
        }
        return try {
            sub.execute(sender, remaining)
        } catch (t: Throwable) {
            sender.sendMessage("${error}Command failed: ${t.message}")
            true
        }
    }

    private fun allLeafCommands(): List<Subcommand> = buildList {
        fun walk(list: List<Subcommand>) {
            for (s in list) {
                if (s.children.isEmpty()) add(s)
                else walk(s.children)
            }
        }
        walk(subcommands)
    }

    private fun help(sender: CommandSender, label: String, page: Int) {
        val accessible = collectVisible(subcommands, sender)
            .sortedWith(compareBy<Subcommand> { it.depth() }.thenBy { it.name })
        val pages = (accessible.size + maxPerPage - 1) / maxPerPage
        val displayPages = if (pages == 0) 1 else pages
        val p = page.coerceIn(1, displayPages)
        sender.sendMessage("${headerColor}${ChatColor.BOLD}$name Commands ${ChatColor.GRAY}(Page $p/$displayPages)${ChatColor.RESET}")
        if (accessible.isEmpty()) {
            sender.sendMessage("${ChatColor.DARK_GRAY}No commands available.")
            return
        }
        val sliceStart = (p - 1) * maxPerPage
        val slice = accessible.subList(sliceStart, min(sliceStart + maxPerPage, accessible.size))
        slice.forEach { sc -> sender.sendMessage("${accent}/${label} ${sc.fullUsage()} ${ChatColor.GRAY}- ${sc.description}") }
        if (displayPages > 1) sender.sendMessage("${ChatColor.DARK_GRAY}Use /$label help <page>")
        sender.sendMessage("${ChatColor.DARK_GRAY}Details: /$label help <subpath> (use : for nesting, e.g. queue:status)")
    }

    private fun sendSingleHelp(sender: CommandSender, label: String, sub: Subcommand) {
        sender.sendMessage("${ChatColor.AQUA}${ChatColor.BOLD}Help: ${sub.path()}${ChatColor.RESET}")
        sender.sendMessage("${ChatColor.GRAY}Description: ${ChatColor.WHITE}${sub.description}")
        sender.sendMessage("${ChatColor.GRAY}Usage: ${ChatColor.WHITE}/$label ${sub.fullUsage()}")
        if (sub.aliases.isNotEmpty()) sender.sendMessage("${ChatColor.GRAY}Aliases: ${ChatColor.WHITE}${sub.aliases.joinToString(", ")}")
        if (sub.children.isNotEmpty()) {
            sender.sendMessage("${ChatColor.GRAY}Children:")
            sub.children.sortedBy { it.name }.forEach { c -> sender.sendMessage("  ${ChatColor.YELLOW}${c.name}${ChatColor.GRAY} - ${c.description}") }
        }
    }

    private fun collectVisible(nodes: List<Subcommand>, sender: CommandSender): List<Subcommand> {
        val list = mutableListOf<Subcommand>()
        for (sc in nodes) {
            if (sc.hidden) continue
            if (sc.permission != null && !sender.hasPermission(sc.permission)) continue
            if (sc.isExecutable()) list += sc
            list += collectVisible(sc.children, sender)
        }
        return list
    }

    private fun resolveExecutable(args: List<String>, candidates: List<Subcommand>, depth: Int = 0): Pair<Subcommand, Int>? {
        if (args.isEmpty()) return null
        val token = args[0].lowercase()
        val match = candidates.firstOrNull { it.matches(token) } ?: return null
        if (match.children.isEmpty() || args.size == 1) {
            return if (match.children.isEmpty()) match to depth + 1 else if (match.isExecutable()) match to depth + 1 else null
        }
        val child = resolveExecutable(args.drop(1), match.children, depth + 1)
        return child ?: if (match.isExecutable()) match to depth + 1 else null
    }

    private fun resolvePath(path: List<String>): Subcommand? {
        var current: List<Subcommand> = subcommands
        var found: Subcommand? = null
        for (segment in path) {
            val seg = segment.lowercase()
            found = current.firstOrNull { it.matches(seg) } ?: return null
            current = found.children
        }
        return found
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val pathTokens = args.dropLast(1)
        var current: List<Subcommand> = subcommands
        var node: Subcommand? = null
        for (token in pathTokens) {
            val next = current.firstOrNull { it.matches(token.lowercase()) }
            if (next == null) return emptyList()
            node = next
            current = next.children
        }
        val last = args.last().lowercase()
        // Custom completion for executable node
        if (node != null && (node.children.isEmpty() || node.isExecutable())) {
            val depth = node.depth()
            if (args.size - 1 == depth && node.tabComplete != null) {
                val remainingArgs = args.drop(depth + 1)
                return node.tabComplete.invoke(sender, remainingArgs).filter { it.startsWith(last, true) }
            }
        }
        return current.filter { it.permission == null || sender.hasPermission(it.permission) }
            .filter { !it.hidden }
            .map { it.name }
            .filter { it.startsWith(last, true) }
    }
}

class Subcommand(
    val name: String,
    val description: String,
    val usage: String = name,
    val aliases: List<String> = emptyList(),
    val permission: String? = null,
    val playerOnly: Boolean = false,
    val minArgs: Int = 0,
    val maxArgs: Int = Int.MAX_VALUE,
    val hidden: Boolean = false,
    val tabComplete: ((CommandSender, List<String>) -> List<String>)? = null,
    val executor: (CommandSender, List<String>) -> Boolean = { _, _ -> true }
) {
    internal val children: MutableList<Subcommand> = mutableListOf()
    internal var parent: Subcommand? = null

    fun child(sc: Subcommand): Subcommand { sc.parent = this; children += sc; return this }

    fun execute(sender: CommandSender, args: List<String>): Boolean = executor(sender, args)

    fun matches(input: String): Boolean = input.equals(name, true) || aliases.any { it.equals(input, true) }

    fun isExecutable(): Boolean = executor !== NOOP || children.isEmpty()

    fun path(): String = generateSequence(this) { it.parent }.toList().asReversed().joinToString(":") { it.name }

    fun depth(): Int { var d = 0; var p = parent; while (p != null) { d++; p = p.parent }; return d }

    fun fullUsage(): String = buildString {
        append(path().replace(":", " "))
        if (usage != name) {
            val extra = usage.removePrefix(name)
            if (extra.isNotBlank()) append(extra)
        }
    }

    companion object { private val NOOP: (CommandSender, List<String>) -> Boolean = { _, _ -> true } }
}
