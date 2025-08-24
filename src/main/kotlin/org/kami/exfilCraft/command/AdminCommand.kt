package org.kami.exfilCraft.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.kami.exfilCraft.ExfilCraft
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.raid.RaidService
import org.bukkit.inventory.ItemFlag
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier

class AdminCommand(
    private val plugin: ExfilCraft,
    private val bunkers: BunkerService,
    private val raids: RaidService,
    private val config: ConfigService
) : CommandExecutor {

    private fun needPlayer(sender: CommandSender): Player? {
        if (sender !is Player) { sender.sendMessage("Only players."); return null }
        return sender
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val p = needPlayer(sender) ?: return true
        if (!p.hasPermission("exfil.admin")) { p.sendMessage("§cNo permission."); return true }
        if (args.isEmpty()) {
            p.sendMessage("§e/$label bunker <reset|realloc> [player]")
            p.sendMessage("§e/$label raid <forcestart [template]|forceend [player|all]|forceextract <player> [alive]|unlock [template|player]|prune|sessions>")
            p.sendMessage("§e/$label unlock extracts  §7- unlock extraction for all active raids")
            p.sendMessage("§e/$label give <compass|ostk>")
            return true
        }
        when(args[0].lowercase()) {
            "bunker" -> handleBunker(p, args.drop(1))
            "raid" -> handleRaid(p, args.drop(1))
            "give" -> handleGive(p, args.drop(1))
            "unlock" -> handleUnlock(p, args.drop(1))
            else -> p.sendMessage("§cUnknown admin subcommand")
        }
        return true
    }

    private fun handleBunker(p: Player, args: List<String>) {
        if (args.isEmpty()) { p.sendMessage("§eUsage: /admin bunker <reset|realloc> [player]"); return }
        val targetName = args.getOrNull(1) ?: p.name
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) { p.sendMessage("§cPlayer not online: $targetName"); return }
        when(args[0].lowercase()) {
            "reset" -> {
                val had = bunkers.resetBunker(target.uniqueId)
                p.sendMessage(if (had) "§aReset bunker for ${target.name}" else "§eNo bunker to reset for ${target.name}")
            }
            "realloc","reallocate" -> {
                val had = bunkers.resetBunkerAndReallocate(target)
                p.sendMessage("§aReallocated bunker for ${target.name} (previous existed=$had)")
            }
            else -> p.sendMessage("§eUsage: /admin bunker <reset|realloc> [player]")
        }
    }

    private fun handleRaid(p: Player, args: List<String>) {
        if (args.isEmpty()) { p.sendMessage("§eUsage: /admin raid <forcestart|forceend|forceextract|unlock|prune|sessions>"); return }
        when(args[0].lowercase()) {
            "forcestart" -> {
                val template = args.getOrNull(1)
                if (raids.adminForceStart(template, p)) p.sendMessage("§aForced start executed")
            }
            "forceend" -> {
                val token = args.getOrNull(1)
                val count = when {
                    token == null -> raids.adminForceEnd(null,false,p).also { if (it==0) p.sendMessage("§7Specify player or 'all'") }
                    token.equals("all", true) -> raids.adminForceEnd(null,true,p)
                    else -> {
                        val target = Bukkit.getPlayerExact(token)
                        if (target==null) { p.sendMessage("§cPlayer not found: $token"); 0 } else raids.adminForceEnd(target,false,p)
                    }
                }
                if (count>0) p.sendMessage("§aEnded $count session(s)")
            }
            "forceextract" -> {
                val targetName = args.getOrNull(1); if (targetName==null) { p.sendMessage("§eUsage: /admin raid forceextract <player> [alive]"); return }
                val target = Bukkit.getPlayerExact(targetName); if (target==null) { p.sendMessage("§cPlayer not online: $targetName"); return }
                val alive = args.getOrNull(2)?.equals("alive", true) == true
                if (raids.adminForceExtract(target, alive, p)) p.sendMessage("§aForce extracted ${target.name}")
            }
            "unlock" -> {
                val token = args.getOrNull(1)
                if (raids.adminUnlockExtraction(p, token)) p.sendMessage("§aExtraction unlocked")
            }
            "prune" -> {
                val removed = raids.pruneStaleWorldFolders()
                p.sendMessage("§e[Admin] Pruned $removed stale raid world folder(s)")
            }
            "sessions" -> raids.adminListSessions(p)
            else -> p.sendMessage("§eUsage: /admin raid <forcestart|forceend|forceextract|unlock|prune|sessions>")
        }
    }

    private fun handleGive(p: Player, args: List<String>) {
        if (args.isEmpty()) { p.sendMessage("§eUsage: /admin give <compass|ostk>"); return }
        when(args[0].lowercase()) {
            "compass" -> {
                val sess = raids.getSessionFor(p.uniqueId)
                if (sess == null) p.sendMessage("§cNot in raid") else raids.giveCompassForReconnect(p, sess).also { p.sendMessage("§aGiven extraction compass") }
            }
            "ostk" -> {
                val item = OstkFactory.create()
                p.inventory.addItem(item)
                p.sendMessage("§cGiven OSTK Blade (10 uses)")
            }
            else -> p.sendMessage("§eUnknown item")
        }
    }

    private fun handleUnlock(p: Player, args: List<String>) {
        if (args.isEmpty()) { p.sendMessage("§eUsage: /admin unlock extracts [template|player]"); return }
        if (args[0].equals("extracts", true)) {
            val token = args.getOrNull(1)
            if (raids.adminUnlockExtraction(p, token)) p.sendMessage("§aExtraction(s) unlocked")
        } else p.sendMessage("§eUsage: /admin unlock extracts [template|player]")
    }

    companion object OstkFactory {
        private val KEY: org.bukkit.NamespacedKey = org.bukkit.NamespacedKey.fromString("exfilcraft:ostk") ?: org.bukkit.NamespacedKey.minecraft("ostk")
        fun create(): org.bukkit.inventory.ItemStack {
            val stack = org.bukkit.inventory.ItemStack(org.bukkit.Material.NETHERITE_SWORD)
            val meta = stack.itemMeta
            meta?.setDisplayName("§cOSTK Blade")
            meta?.lore = listOf("§7One Shot Kill", "§710 uses", "§cNo enchanting")
            if (meta is org.bukkit.inventory.meta.Damageable) {
                val max = stack.type.maxDurability.toInt()
                val keep = 10
                meta.damage = (max - keep).coerceAtLeast(0)
            }
            // Resolve attack damage attribute constant across versions
            val attackAttr = runCatching { Attribute.valueOf("GENERIC_ATTACK_DAMAGE") }.getOrElse { runCatching { Attribute.valueOf("ATTACK_DAMAGE") }.getOrNull() }
            if (attackAttr != null) {
                meta?.addAttributeModifier(attackAttr,
                    AttributeModifier(java.util.UUID.randomUUID(), "ostk_dmg", 2048.0, AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlot.HAND))
            }
            meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
            meta?.persistentDataContainer?.set(KEY, org.bukkit.persistence.PersistentDataType.BYTE, 1)
            stack.itemMeta = meta
            return stack
        }
        fun isOstk(item: org.bukkit.inventory.ItemStack?): Boolean {
            if (item==null) return false
            val meta = item.itemMeta ?: return false
            return meta.persistentDataContainer.has(KEY, org.bukkit.persistence.PersistentDataType.BYTE)
        }
    }
}
