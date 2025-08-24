package org.kami.exfilCraft

import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.command.*
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.core.ServiceRegistry
import org.kami.exfilCraft.core.log
import org.kami.exfilCraft.db.DatabaseService
import org.kami.exfilCraft.listener.JoinListener
import org.kami.exfilCraft.listener.RaidListener
import org.kami.exfilCraft.listener.GlobalRestrictionListener
import org.kami.exfilCraft.listener.RaidTemplateListener
import org.kami.exfilCraft.listener.PortalAndItemListener
import org.kami.exfilCraft.profile.ProfileService
import org.kami.exfilCraft.raid.RaidService
import org.kami.exfilCraft.world.WorldManagerService
import org.kami.exfilCraft.logging.LoggingService
import org.kami.exfilCraft.team.TeamService
import org.kami.exfilCraft.command.framework.RootCommand
import org.kami.exfilCraft.command.framework.Subcommand
import org.bukkit.ChatColor
import org.bukkit.entity.Player

// Unified command system version 1: powered by RootCommand/ Subcommand framework
open class ExfilCraft : JavaPlugin() {

    lateinit var services: ServiceRegistry
        private set

    override fun onEnable() {
        saveDefaultConfig()
        services = ServiceRegistry(this)
        val configService = ConfigService(this); services.register(configService)
        val logging = LoggingService(this, configService); services.register(logging)
        val coreLog = logging.section("CORE")
        val db = DatabaseService(this, configService, logging); services.register(db)
        val profileService = ProfileService(this, db); services.register(profileService)
        val bunkerService = BunkerService(this, db, configService, profileService, logging); services.register(bunkerService)
        val teamService = TeamService(); services.register(teamService)
        val worldManager = WorldManagerService(this, configService, logging); services.register(worldManager)
        val raidService = RaidService(this, configService, bunkerService, worldManager, teamService, logging = logging); services.register(raidService)

        try {
            db.init(); bunkerService.ensureBunkerWorld()
        } catch (t: Throwable) {
            coreLog.error("Startup failure", t); server.pluginManager.disablePlugin(this); return
        }

        registerCommand("exfil", buildExfilRoot(configService))
        registerCommand("bunker", buildBunkerRoot(bunkerService, profileService, configService))
        registerCommand("team", buildTeamRoot(teamService))
        registerCommand("raid", buildRaidRoot(raidService))
        registerCommand("admin", buildAdminRoot(bunkerService, raidService, configService))

        registerListener(JoinListener(profileService, bunkerService, configService, raidService))
        registerListener(org.kami.exfilCraft.listener.BunkerProtectionListener(this, configService, bunkerService))
        registerListener(RaidListener(raidService))
        registerListener(GlobalRestrictionListener(configService))
        registerListener(RaidTemplateListener(this, raidService))
        registerListener(PortalAndItemListener(raidService))
        coreLog.info("Enabled version ${description.version}")
    }

    override fun onDisable() {
        services.getOptional<RaidService>()?.shutdown()
        services.getOptional<DatabaseService>()?.close()
        services.getOptional<LoggingService>()?.section("CORE")?.info("Disabled") ?: log(this, "Disabled")
    }

    private fun registerCommand(name: String, root: RootCommand) { getCommand(name)?.apply { setExecutor(root); tabCompleter = root } }
    private fun registerListener(listener: Listener) { server.pluginManager.registerEvents(listener, this) }

    // -------- Command builders --------
    private fun buildExfilRoot(config: ConfigService): RootCommand {
        val root = RootCommand("Exfil", "Base plugin command")
        root.register(Subcommand("version", "Show plugin version") { sender, _ ->
            sender.sendMessage("${ChatColor.AQUA}ExfilCraft ${description.version} by ${description.authors.joinToString()}"); true
        })
        root.register(Subcommand("debug", "Toggle debug mode", permission = "exfil.admin") { sender, _ ->
            val new = !config.debugEnabled
            // Use this plugin instance directly instead of accessing private field inside ConfigService
            this@ExfilCraft.config.set("debug", new)
            this@ExfilCraft.saveConfig()
            config.load()
            sender.sendMessage("${ChatColor.GREEN}Debug set to $new"); true
        })
        root.register(Subcommand("reload", "Reload configuration", permission = "exfil.admin") { sender, _ ->
            config.reload(); sender.sendMessage("${ChatColor.GREEN}Config reloaded"); true
        })
        return root
    }

    private fun buildBunkerRoot(bunkers: BunkerService, profiles: ProfileService, config: ConfigService): RootCommand {
        val dirs = listOf("north","south","east","west","n","s","e","w")
        val root = RootCommand("Bunker", "Manage your personal bunker", allowConsole = false)
        root.register(Subcommand("info", "Show bunker info", playerOnly = true) { sender, _ ->
            val p = sender as Player
            val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: run {
                p.sendMessage("${ChatColor.YELLOW}Allocating bunker...")
                val b = bunkers.allocateIfAbsent(p); bunkers.teleportToBunker(p, b)
                p.sendMessage("${ChatColor.GREEN}Bunker created."); return@Subcommand true
            }
            val cooldown = bunkers.getExpansionCooldownRemainingSeconds(bunker)
            val nextCost = bunkers.nextExpansionCostXp(bunker)
            val members = bunkers.getMembers(bunker).mapNotNull { p.server.getPlayer(it)?.name ?: p.server.getOfflinePlayer(it).name ?: it.toString().substring(0,8) }
            p.sendMessage("${ChatColor.AQUA}Bunker Info:")
            p.sendMessage("${ChatColor.GRAY}Origin Chunk: ${bunker.originChunkX}, ${bunker.originChunkZ}")
            p.sendMessage("${ChatColor.GRAY}Cubes: ${bunker.cubesCount}")
            p.sendMessage("${ChatColor.GRAY}Cube Size: ${bunker.cubeSize}")
            p.sendMessage("${ChatColor.GRAY}Members (${members.size}/4): ${members.joinToString(", ")}")
            p.sendMessage("${ChatColor.GRAY}Next Expansion Cost: ${nextCost} XP")
            p.sendMessage("${ChatColor.GRAY}Expansion Cooldown: ${cooldown}s")
            true
        })
        root.register(Subcommand("warp", "Teleport to (or create) your bunker", playerOnly = true) { sender, _ ->
            val p = sender as Player
            val mainWorld = p.server.worlds.firstOrNull()?.name
            if (mainWorld == null) { p.sendMessage("${ChatColor.RED}Primary world unavailable."); return@Subcommand true }
            if (p.world.name != mainWorld) { p.sendMessage("${ChatColor.RED}Use only in main world."); return@Subcommand true }
            if (p.world.name == config.bunkerWorldName) { p.sendMessage("${ChatColor.RED}Already in bunker world."); return@Subcommand true }
            val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: bunkers.allocateIfAbsent(p)
            bunkers.teleportToBunker(p, bunker); p.sendMessage("${ChatColor.GREEN}Warped to your bunker."); true
        })
        root.register(Subcommand("extend", "Expand bunker in a direction", usage = "extend [direction]", playerOnly = true, maxArgs = 1, tabComplete = { _, _ -> dirs }) { sender, args ->
            val p = sender as Player
            val bunker = bunkers.getBunkerForPlayer(p.uniqueId) ?: run { p.sendMessage("${ChatColor.YELLOW}No bunker found, allocating..."); bunkers.allocateIfAbsent(p) }
            val b = bunkers.getBunkerForPlayer(p.uniqueId) ?: return@Subcommand true
            val dirToken = args.getOrNull(0)
            val explicit = dirToken?.let { bunkers.parseDirectionToken(it) }
            if (dirToken != null && explicit == null) { p.sendMessage("${ChatColor.RED}Unknown direction '${dirToken}'."); return@Subcommand true }
            if (!bunkers.extendBunkerFacing(p, b, explicit)) {
                val cur = bunkers.getCubeContaining(b, p.location.blockX, p.location.blockY, p.location.blockZ) ?: Triple(0,0,0)
                val avail = bunkers.availableAdjacentDirections(b, cur.first, cur.third)
                if (avail.isNotEmpty()) p.sendMessage("${ChatColor.GRAY}Available: ${avail.map { bunkers.directionName(it) }.joinToString(", ")}")
            }
            true
        })
        root.register(Subcommand("invite", "Invite a player to your bunker", usage = "invite <player>", playerOnly = true, minArgs = 1, maxArgs = 1, tabComplete = { sender, _ -> sender.server.onlinePlayers.map { it.name } }) { sender, args ->
            val p = sender as Player
            val target = p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Player not online"); return@Subcommand true }
            if (target.uniqueId == p.uniqueId) { p.sendMessage("${ChatColor.RED}Cannot invite yourself."); return@Subcommand true }
            if (bunkers.inviteMember(p.uniqueId, target.uniqueId)) {
                p.sendMessage("${ChatColor.GREEN}Invite sent to ${target.name}.")
                target.sendMessage("${ChatColor.AQUA}${p.name} invited you. Use /bunker accept ${p.name}")
            } else p.sendMessage("${ChatColor.RED}Invite failed.")
            true
        })
        root.register(Subcommand("accept", "Accept a bunker invite", usage = "accept <player>", playerOnly = true, minArgs = 1, maxArgs = 1, tabComplete = { sender, _ -> sender.server.onlinePlayers.map { it.name } }) { sender, args ->
            val p = sender as Player
            val inviter = p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Inviter must be online."); return@Subcommand true }
            if (bunkers.acceptInvite(p.uniqueId, inviter.uniqueId)) {
                p.sendMessage("${ChatColor.GREEN}Joined bunker of ${inviter.name}.")
                inviter.sendMessage("${ChatColor.AQUA}${p.name} accepted your bunker invite.")
            } else p.sendMessage("${ChatColor.RED}Accept failed.")
            true
        })
        return root
    }

    private fun buildTeamRoot(teams: TeamService): RootCommand {
        val root = RootCommand("Team", "Temporary raid team management", allowConsole = false)
        root.register(Subcommand("create", "Create a team", playerOnly = true) { s,_ ->
            val p = s as Player
            when {
                teams.getTeam(p.uniqueId) != null -> p.sendMessage("${ChatColor.RED}Already in a team.")
                teams.create(p.uniqueId) -> p.sendMessage("${ChatColor.GREEN}Team created. You are leader.")
                else -> p.sendMessage("${ChatColor.RED}Failed to create team.")
            }; true
        })
        root.register(Subcommand("disband", "Disband your team", playerOnly = true) { s,_ ->
            val p = s as Player; val t = teams.getTeam(p.uniqueId)
            when {
                t == null -> p.sendMessage("${ChatColor.RED}Not in a team.")
                t.leader != p.uniqueId -> p.sendMessage("${ChatColor.RED}Only leader can disband.")
                teams.disband(p.uniqueId) -> p.sendMessage("${ChatColor.YELLOW}Team disbanded.")
            }; true
        })
        root.register(Subcommand("leave", "Leave your current team", playerOnly = true) { s,_ ->
            val p = s as Player; val t = teams.getTeam(p.uniqueId)
            when {
                t == null -> p.sendMessage("${ChatColor.RED}Not in a team.")
                t.leader == p.uniqueId -> p.sendMessage("${ChatColor.RED}Leader must disband instead.")
                teams.leave(p.uniqueId) -> p.sendMessage("${ChatColor.YELLOW}You left the team.")
            }; true
        })
        root.register(Subcommand("invite", "Invite a player", usage = "invite <player>", playerOnly = true, minArgs = 1, maxArgs = 1, tabComplete = { sender,_ -> sender.server.onlinePlayers.map { it.name } }) { s,args ->
            val p = s as Player; val t = teams.getTeam(p.uniqueId)
            val target = p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Player not found."); return@Subcommand true }
            if (t == null || t.leader != p.uniqueId) { p.sendMessage("${ChatColor.RED}You must be team leader."); return@Subcommand true }
            if (teams.invite(p.uniqueId, target.uniqueId)) {
                p.sendMessage("${ChatColor.GREEN}Invited ${target.name}.")
                target.sendMessage("${ChatColor.AQUA}Team invite from ${p.name}. Use /team join ${p.name}")
            } else p.sendMessage("${ChatColor.RED}Invite failed.")
            true
        })
        root.register(Subcommand("join", "Join a leader's team", usage = "join <leader>", playerOnly = true, minArgs = 1, maxArgs = 1, tabComplete = { sender,_ -> sender.server.onlinePlayers.map { it.name } }) { s,args ->
            val p = s as Player
            val leader = p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Leader offline."); return@Subcommand true }
            if (teams.accept(p.uniqueId, leader.uniqueId)) {
                p.sendMessage("${ChatColor.GREEN}Joined ${leader.name}'s team.")
                leader.sendMessage("${ChatColor.AQUA}${p.name} joined the team.")
            } else p.sendMessage("${ChatColor.RED}Join failed.")
            true
        })
        root.register(Subcommand("info", "Show your team info", playerOnly = true) { s,_ ->
            val p = s as Player; val t = teams.getTeam(p.uniqueId)
            if (t == null) p.sendMessage("${ChatColor.YELLOW}Not in a team.") else {
                p.sendMessage("${ChatColor.AQUA}Team Leader: ${p.server.getPlayer(t.leader)?.name ?: t.leader}")
                val members = t.members.map { p.server.getPlayer(it)?.name ?: it.toString().substring(0,8) }
                p.sendMessage("${ChatColor.GRAY}Members (${t.members.size}): ${members.joinToString(", ")}")
            }
            true
        })
        return root
    }

    private fun buildRaidRoot(raids: RaidService): RootCommand {
        val root = RootCommand("Raid", "Engage in raids", allowConsole = false)
        root.register(Subcommand("start", "Open raid template menu", playerOnly = true) { s,_ -> raids.openTemplateMenu(s as Player); true })
        root.register(Subcommand("status", "Show your raid status", playerOnly = true) { s,_ -> raids.status(s as Player); true })
        val queue = Subcommand("queue", "Raid queue management")
        queue.child(Subcommand("leave", "Leave the queue", playerOnly = true) { s,_ -> raids.leaveQueue(s as Player); true })
        queue.child(Subcommand("status", "Show queue status", playerOnly = true) { s,_ -> raids.queueStatus(s as Player); true })
        queue.child(Subcommand("available", "List available players", playerOnly = true) { s,_ ->
            val avail = raids.availablePlayers(); s.sendMessage("${ChatColor.AQUA}Available (${avail.size}): ${avail.joinToString { it.name }}"); true })
        root.register(queue)
        return root
    }

    private fun buildAdminRoot(bunkers: BunkerService, raids: RaidService, config: ConfigService): RootCommand {
        val root = RootCommand("Admin", "Administrative commands", permission = "exfil.admin", allowConsole = false)
        val bunker = Subcommand("bunker", "Manage player bunkers")
        bunker.child(Subcommand("reset", "Reset a player's bunker", usage = "reset [player]", playerOnly = true, maxArgs = 1, tabComplete = { s,_ -> s.server.onlinePlayers.map { it.name } }) { s,args ->
            val p = s as Player; val target = if (args.isEmpty()) p else p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Player offline"); return@Subcommand true }
            val had = bunkers.resetBunker(target.uniqueId)
            p.sendMessage("${ChatColor.GREEN}Reset bunker for ${target.name} (existed=$had)"); true
        })
        bunker.child(Subcommand("realloc", "Reset and reallocate bunker", usage = "realloc [player]", playerOnly = true, maxArgs = 1, tabComplete = { s,_ -> s.server.onlinePlayers.map { it.name } }) { s,args ->
            val p = s as Player; val target = if (args.isEmpty()) p else p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Player offline"); return@Subcommand true }
            val had = bunkers.resetBunkerAndReallocate(target)
            p.sendMessage("${ChatColor.GREEN}Reallocated bunker for ${target.name} (previous existed=$had)"); true
        })
        root.register(bunker)
        val raid = Subcommand("raid", "Raid session control")
        raid.child(Subcommand("forcestart", "Force start a raid", usage = "forcestart [template]", playerOnly = true, maxArgs = 1, tabComplete = { _,_ -> config.raidTemplates().map { it.id } }) { s,args ->
            val p = s as Player; if (raids.adminForceStart(args.firstOrNull(), p)) p.sendMessage("${ChatColor.GREEN}Forced start executed"); true })
        raid.child(Subcommand("forceend", "Force end raids", usage = "forceend [player|all]", playerOnly = true, maxArgs = 1, tabComplete = { s,_ -> s.server.onlinePlayers.map { it.name } + listOf("all") }) { s,args ->
            val p = s as Player; val token = args.firstOrNull(); val count = when {
                token == null -> raids.adminForceEnd(null,false,p)
                token.equals("all", true) -> raids.adminForceEnd(null,true,p)
                else -> {
                    val t = p.server.getPlayerExact(token); if (t==null) { p.sendMessage("${ChatColor.RED}Player not found"); 0 } else raids.adminForceEnd(t,false,p)
                }
            }; if (count>0) p.sendMessage("${ChatColor.GREEN}Ended $count session(s)"); true })
        raid.child(Subcommand("forceextract", "Force extract a player", usage = "forceextract <player> [alive]", playerOnly = true, minArgs = 1, maxArgs = 2, tabComplete = { s,args -> if (args.size <=1) s.server.onlinePlayers.map { it.name } else listOf("alive") }) { s,args ->
            val p = s as Player; val target = p.server.getPlayerExact(args[0]) ?: run { p.sendMessage("${ChatColor.RED}Player not online"); return@Subcommand true }
            val alive = args.getOrNull(1)?.equals("alive", true) == true
            if (raids.adminForceExtract(target, alive, p)) p.sendMessage("${ChatColor.GREEN}Force extracted ${target.name}"); true })
        raid.child(Subcommand("unlock", "Unlock extraction(s)", usage = "unlock [template|player]", playerOnly = true, maxArgs = 1, tabComplete = { s,_ -> config.raidTemplates().map { it.id } + s.server.onlinePlayers.map { it.name } }) { s,args ->
            val p = s as Player; if (raids.adminUnlockExtraction(p, args.firstOrNull())) p.sendMessage("${ChatColor.GREEN}Extraction unlocked"); true })
        raid.child(Subcommand("prune", "Prune stale raid worlds", playerOnly = true) { s,_ ->
            val p = s as Player; val removed = raids.pruneStaleWorldFolders(); p.sendMessage("${ChatColor.YELLOW}Pruned $removed stale raid world(s)"); true })
        raid.child(Subcommand("sessions", "List active raid sessions", playerOnly = true) { s,_ -> raids.adminListSessions(s as Player); true })
        root.register(raid)
        val give = Subcommand("give", "Give test/admin items")
        give.child(Subcommand("compass", "Give extraction compass", playerOnly = true) { s,_ ->
            val p = s as Player; val sess = raids.getSessionFor(p.uniqueId); if (sess == null) p.sendMessage("${ChatColor.RED}Not in raid") else { raids.giveCompassForReconnect(p, sess); p.sendMessage("${ChatColor.GREEN}Given extraction compass") }; true })
        give.child(Subcommand("ostk", "Give OSTK blade", playerOnly = true) { s,_ ->
            val p = s as Player; val item = AdminCommand.OstkFactory.create(); p.inventory.addItem(item); p.sendMessage("${ChatColor.RED}Given OSTK Blade") ; true })
        root.register(give)
        return root
    }
}
