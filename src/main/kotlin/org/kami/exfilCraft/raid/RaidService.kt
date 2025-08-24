package org.kami.exfilCraft.raid

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.bunker.BunkerService
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.logging.LoggingService
import org.kami.exfilCraft.team.TeamService
import org.kami.exfilCraft.world.WorldManagerService
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import org.bukkit.NamespacedKey

// New world factory abstraction for test harness
interface RaidWorldFactory {
    fun create(template: org.kami.exfilCraft.raid.RaidTemplate, id: Long): World?
}

class DefaultRaidWorldFactory(private val plugin: JavaPlugin, private val config: org.kami.exfilCraft.core.ConfigService): RaidWorldFactory {
    private fun applyWorldOptimizations(world: World) {
        if (config.worldDisableAutosave) world.isAutoSave = false
        world.difficulty = Difficulty.HARD
        config.worldGamerules().forEach { (k,v) ->
            try {
                @Suppress("UNCHECKED_CAST")
                val gr = GameRule.getByName(k) as GameRule<Any>?
                if (gr != null && v != null) world.setGameRule(gr, v)
            } catch (_: Throwable) { }
        }
    }
    override fun create(template: org.kami.exfilCraft.raid.RaidTemplate, id: Long): World? {
        val name = "exfil_raid_${System.currentTimeMillis()}_${id}_${template.id}"
        val creator = WorldCreator(name).environment(template.environment).seed(java.util.concurrent.ThreadLocalRandom.current().nextLong())
        val w = creator.createWorld() ?: return null
        applyWorldOptimizations(w)
        return w
    }
}

class RaidService(
    private val plugin: JavaPlugin,
    private val config: ConfigService,
    private val bunkers: org.kami.exfilCraft.bunker.BunkerService,
    private val worldManager: org.kami.exfilCraft.world.WorldManagerService,
    private val teams: org.kami.exfilCraft.team.TeamService,
    private var worldFactory: RaidWorldFactory = DefaultRaidWorldFactory(plugin, config),
    logging: LoggingService
) {
    private val log = logging.section("RAID")
    private val spawnLog = log.sub("SPAWN")
    private val worldGenLog = log.sub("WORLDGEN")

    data class QueuedTeam(val leader: UUID, val members: Set<UUID>, val joinedAt: Long = System.currentTimeMillis())
    // Template queues
    private val templateQueuesTeams = mutableMapOf<String, MutableList<QueuedTeam>>()
    private val playerTemplateChoice = mutableMapOf<UUID, String>()
    private val menuTitle = "§8Select Raid Template"
    // legacy single queue kept for backward compatibility (unused after GUI)
    private val sessions = mutableMapOf<Long, RaidSession>()
    private val playerSession = mutableMapOf<UUID, Long>()
    private val idSeq = AtomicLong(1)
    private var taskId: Int = -1
    private val queue = LinkedHashSet<UUID>()
    private val pendingReturn = mutableSetOf<UUID>()
    private val lastRaidOutcome = mutableMapOf<UUID, String>()

    private var queueCountdownStart: Long? = null
    private var queueCountdownTask: Int = -1
    private val actionBarRateLimiter = mutableMapOf<UUID, Long>()
    private val extractionLastSecondShown = mutableMapOf<UUID, Int>()

    private val spawnProtectionSeconds = 5 // default; template override may change per session

    init { startHeartbeat() }

    fun inRaid(p: UUID): Boolean = playerSession.containsKey(p)
    fun getSessionFor(p: UUID): RaidSession? = playerSession[p]?.let { sessions[it] }
    fun isPendingReturn(uuid: UUID) = pendingReturn.contains(uuid)
    fun isUnderSpawnProtection(uuid: UUID): Boolean { val session = getSessionFor(uuid) ?: return false; return (System.currentTimeMillis()/1000L) < session.spawnProtectionEndsAt }

    fun queueAndMaybeStart(player: Player) {
        if (inRaid(player.uniqueId)) { player.sendMessage("§cAlready in a raid."); return }
        if (queue.contains(player.uniqueId)) { player.sendMessage("§eAlready queued."); return }
        queue += player.uniqueId
        player.sendMessage("§aQueued for raid. (${queue.size}/${config.raidMinPlayers})")
        updateQueueCountdown()
    }

    private fun updateQueueCountdown() {
        val minPlayers = config.raidMinPlayers
        if (queue.size >= minPlayers) {
            // Respect max concurrent sessions
            if (sessions.count { it.value.state == RaidState.ACTIVE } >= config.raidMaxConcurrent) {
                broadcastQueue("§cRaid cannot start (server at max active raids). Waiting...")
                return
            }
            // If auto-start enabled, skip countdown entirely
            if (config.raidQueueAutoStart) {
                val participants = queue.toList()
                queue.clear()
                cancelQueueCountdown()
                showTitle(participants, "§aStarting", "§7Teleporting...", 0, 40, 10)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    startSession(participants.mapNotNull { Bukkit.getPlayer(it) })
                }, 20L) // 1 second grace
                return
            }
            // start countdown if not active
            if (queueCountdownStart == null) {
                val now = System.currentTimeMillis() / 1000L
                queueCountdownStart = now
                startQueueCountdownTask()
                broadcastQueue("§aRaid starting countdown begun (${queue.size}/$minPlayers)")
            }
        } else {
            // not enough players, cancel any countdown
            cancelQueueCountdown()
        }
    }

    private fun startQueueCountdownTask() {
        cancelQueueCountdown()
        val duration = config.raidQueueCountdownSeconds.toLong().coerceAtLeast(3)
        queueCountdownTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            val start = queueCountdownStart ?: return@Runnable
            val now = System.currentTimeMillis() / 1000L
            val elapsed = now - start
            val remaining = duration - elapsed
            if (remaining <= 0) {
                // include any late joiners before teleport
                val participants = queue.toList()
                queue.clear()
                cancelQueueCountdown()
                showTitle(participants, "§aStarting", "§7Teleporting...", 0, 40, 10)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    startSession(participants.mapNotNull { Bukkit.getPlayer(it) })
                }, 20L) // 1 second grace
                return@Runnable
            }
            val seconds = remaining.toInt()
            val title = if (seconds <= 5) "§c$seconds" else "§e$seconds"
            val subtitle = "§7Raid starts soon"
            showTitle(queue, title, subtitle, 0, 20, 0)
        }, 0L, 20L)
    }

    private fun cancelQueueCountdown() {
        if (queueCountdownTask != -1) Bukkit.getScheduler().cancelTask(queueCountdownTask)
        queueCountdownTask = -1
        queueCountdownStart = null
    }

    private fun broadcastQueue(msg: String) { queue.forEach { Bukkit.getPlayer(it)?.sendMessage(msg) } }

    private fun showTitle(targets: Collection<UUID>, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        targets.forEach { uuid -> Bukkit.getPlayer(uuid)?.sendTitle(title, subtitle, fadeIn, stay, fadeOut) }
    }

    private fun sendActionBar(uuid: UUID, text: String) {
        val p = Bukkit.getPlayer(uuid) ?: return
        val now = System.currentTimeMillis()
        val last = actionBarRateLimiter[uuid] ?: 0L
        if (now - last < 300) return
        actionBarRateLimiter[uuid] = now
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(text))
    }

    private fun chooseBossBar(): org.bukkit.boss.BossBar {
        val color = runCatching { BarColor.valueOf(config.raidBossBarColor.uppercase()) }.getOrElse { BarColor.GREEN }
        val style = runCatching { BarStyle.valueOf(config.raidBossBarStyle.uppercase()) }.getOrElse { BarStyle.SOLID }
        return Bukkit.createBossBar(config.raidBossBarTitle, color, style)
    }

    // --- Extraction & world selection helpers ---
    private val disallowedMaterials = setOf(Material.WATER, Material.LAVA, Material.MAGMA_BLOCK, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS)
    private fun isBadTop(mat: Material) = disallowedMaterials.contains(mat)
    private fun isTreeBlock(mat: Material) = mat.name.endsWith("_LEAVES") || mat.name.endsWith("_LOG") || mat.name.endsWith("_WOOD")

    private fun highestStableGround(world: World, x: Int, z: Int): Location? {
        if (world.environment == World.Environment.NETHER) {
            val y = netherGroundY(world, x, z) ?: return null
            val base = world.getBlockAt(x, y, z)
            if (!base.type.isSolid) return null
            return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        }
        val surface = world.getHighestBlockAt(x, z)
        val y = surface.y
        val mat = surface.type
        if (!mat.isSolid || isBadTop(mat) || isTreeBlock(mat)) return null
        if (!world.getBlockAt(x, y + 1, z).type.isAir()) return null
        if (!world.getBlockAt(x, y + 2, z).type.isAir()) return null
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun relaxedBeachGround(world: World, x: Int, z: Int): Location? { val hb = world.getHighestBlockAt(x, z); val m = hb.type; return if ((m == Material.SAND || m == Material.GRAVEL) && hasAdjacentWater(world, x, hb.y, z)) hb.location else null }
    private fun hasAdjacentWater(world: World, x: Int, y: Int, z: Int): Boolean { for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) if (world.getBlockAt(x+dx,y,z+dz).type == Material.WATER) return true; return false }

    private fun findExtractionPoint(world: World, borderHalf: Double, ref: Location): Location {
        val step = 8.0
        val perimeter = mutableListOf<Pair<Int,Int>>()
        var x = -borderHalf
        while (x <= borderHalf) { perimeter += x.toInt() to (-borderHalf).toInt(); perimeter += x.toInt() to borderHalf.toInt(); x += step }
        var z = -borderHalf
        while (z <= borderHalf) { perimeter += (-borderHalf).toInt() to z.toInt(); perimeter += borderHalf.toInt() to z.toInt(); z += step }
        val seen = HashSet<Pair<Int,Int>>()
        val candidates = mutableListOf<Location>()
        for ((cx, cz) in perimeter) if (seen.add(cx to cz)) highestStableGround(world, cx, cz)?.let { candidates += it.add(0.5,1.0,0.5) }
        val chosen = candidates.maxByOrNull { it.distanceSquared(ref) }
        if (chosen != null) return chosen
        val relaxed = perimeter.mapNotNull { (cx,cz) -> if (seen.add(cx to cz)) relaxedBeachGround(world,cx,cz) else null }.maxByOrNull { it.distanceSquared(ref) }
        if (relaxed != null) return relaxed.add(0.5,1.0,0.5)
        return world.spawnLocation.add(0.5,0.0,0.5)
    }

    private fun isOceanHeavy(world: World, borderHalf: Double): Boolean {
        val threshold = 0.65
        val maxSamples = when {
            borderHalf <= 128 -> 160
            borderHalf <= 256 -> 240
            else -> 320
        }
        val rand = ThreadLocalRandom.current()
        var water = 0
        var total = 0
        val startNs = System.nanoTime()
        // Random stratified sampling: divide area into rings and sample limited points
        while (total < maxSamples) {
            val rx = rand.nextDouble(-borderHalf + 8, borderHalf - 8)
            val rz = rand.nextDouble(-borderHalf + 8, borderHalf - 8)
            val hb = world.getHighestBlockAt(rx.toInt(), rz.toInt())
            total++
            if (hb.type == Material.WATER || hb.type == Material.KELP || hb.type == Material.SEAGRASS || hb.type == Material.TALL_SEAGRASS) water++
            // Early termination if even making all remaining samples land cannot drop below threshold
            val remaining = maxSamples - total
            val worstPossibleWaterRatio = water.toDouble() / (total + remaining)
            if (worstPossibleWaterRatio > threshold) return true
            val bestPossibleWaterRatio = (water + remaining).toDouble() / (total + remaining)
            if (bestPossibleWaterRatio < threshold) return false
            if ((System.nanoTime() - startNs) / 1_000_000 > 40) { // safety cutoff ~40ms
                break
            }
        }
        return total > 0 && water.toDouble() / total > threshold
    }

    // --- Spawn safety helpers ---
    private fun isAir(mat: Material) = mat.isAir
    private fun hasTwoAirAbove(world: World, x: Int, y: Int, z: Int): Boolean {
        return world.getBlockAt(x, y + 1, z).type.isAir && world.getBlockAt(x, y + 2, z).type.isAir
    }

    private fun safeSpawnFromSurface(world: World, x: Int, z: Int): Location? {
        // Nether: use custom ground finder instead of highestBlockAt to avoid roof
        if (world.environment == World.Environment.NETHER) {
            val y = netherGroundY(world, x, z) ?: return null
            val loc = Location(world, x + 0.5, y + 1.0, z + 0.5)
            // final safety: ensure two air above
            return if (world.getBlockAt(x, y + 1, z).type.isAir && world.getBlockAt(x, y + 2, z).type.isAir) loc else null
        }
        val top = world.getHighestBlockAt(x, z)
        // Try top first
        if (top.type.isSolid && !isBadTop(top.type) && !isTreeBlock(top.type) && hasTwoAirAbove(world, x, top.y, z)) {
            return Location(world, x + 0.5, top.y + 1.0, z + 0.5)
        }
        // Optional downward adjustment (inside foliage / on leaves)
        if (config.raidSpawnSafetyEnableDownward) {
            var dy = top.y - 1
            var downCount = 0
            while (downCount < config.raidSpawnSafetyDownwardAdjustMax && dy >= world.minHeight) {
                val b = world.getBlockAt(x, dy, z)
                if (b.type.isSolid && !isBadTop(b.type) && !isTreeBlock(b.type) && hasTwoAirAbove(world, x, dy, z)) {
                    return Location(world, x + 0.5, dy + 1.0, z + 0.5)
                }
                dy--; downCount++
            }
        }
        // Upward adjustment (small hills with overhang etc.)
        var upY = top.y + 1
        var upCount = 0
        while (upCount < config.raidSpawnSafetyUpwardAdjustMax && upY + 2 < world.maxHeight) {
            val below = world.getBlockAt(x, upY - 1, z)
            if (below.type.isSolid && !isBadTop(below.type) && !isTreeBlock(below.type) && hasTwoAirAbove(world, x, upY - 1, z)) {
                return Location(world, x + 0.5, upY.toDouble(), z + 0.5)
            }
            upY++; upCount++
        }
        return null
    }

    private fun randomSafeSpawnNear(world: World, center: Location, radius: Double): Location {
        val startNs = System.nanoTime()
        var radialAttempts = 0
        var squareAttempts = 0
        val rand = ThreadLocalRandom.current()
        // Radial sampling phase
        if (config.raidSpawnSafetyEnableRadial) {
            val samples = config.raidSpawnSafetyRadialSamples
            repeat(samples) {
                val ang = rand.nextDouble(0.0, Math.PI * 2)
                val r = rand.nextDouble(0.0, radius)
                val x = (center.x + Math.cos(ang) * r).toInt()
                val z = (center.z + Math.sin(ang) * r).toInt()
                radialAttempts++
                val loc = safeSpawnFromSurface(world, x, z)
                if (loc != null) {
                    val ms = (System.nanoTime() - startNs) / 1_000_000
                    if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety candidate", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to loc.blockX, "y" to loc.blockY, "z" to loc.blockZ)
                    return loc
                }
            }
        }
        // Square expansion fallback
        if (config.raidSpawnSafetyEnableSquare) {
            val maxR = (radius + config.raidSpawnSafetySquareExtraRadius).toInt()
            for (d in 1..maxR) {
                for (x in (center.blockX - d)..(center.blockX + d)) {
                    val loc1 = safeSpawnFromSurface(world, x, center.blockZ - d); squareAttempts++; if (loc1 != null) { val ms = (System.nanoTime() - startNs) / 1_000_000; if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety candidate", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to loc1.blockX, "y" to loc1.blockY, "z" to loc1.blockZ); return loc1 }
                    val loc2 = safeSpawnFromSurface(world, x, center.blockZ + d); squareAttempts++; if (loc2 != null) { val ms = (System.nanoTime() - startNs) / 1_000_000; if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety candidate", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to loc2.blockX, "y" to loc2.blockY, "z" to loc2.blockZ); return loc2 }
                }
                for (z in (center.blockZ - d + 1)..(center.blockZ + d - 1)) {
                    val loc1 = safeSpawnFromSurface(world, center.blockX - d, z); squareAttempts++; if (loc1 != null) { val ms = (System.nanoTime() - startNs) / 1_000_000; if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety candidate", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to loc1.blockX, "y" to loc1.blockY, "z" to loc1.blockZ); return loc1 }
                    val loc2 = safeSpawnFromSurface(world, center.blockX + d, z); squareAttempts++; if (loc2 != null) { val ms = (System.nanoTime() - startNs) / 1_000_000; if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety candidate", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to loc2.blockX, "y" to loc2.blockY, "z" to loc2.blockZ); return loc2 }
                }
            }
        }
        // Fallback: world spawn surface safe
        val sx = world.spawnLocation.blockX
        val sz = world.spawnLocation.blockZ
        val fallback = safeSpawnFromSurface(world, sx, sz) ?: world.spawnLocation.add(0.5, 1.0, 0.5)
        val ms = (System.nanoTime() - startNs) / 1_000_000
        if (ms > config.raidSpawnSafetyLogThresholdMs) spawnLog.info("Spawn safety fallback", "radialAttempts" to radialAttempts, "squareAttempts" to squareAttempts, "ms" to ms, "x" to fallback.blockX, "y" to fallback.blockY, "z" to fallback.blockZ)
        return fallback
    }

    private fun legacyStartSession(players: List<Player>, template: org.kami.exfilCraft.raid.RaidTemplate) {
        val initialPlayers = players.filter { it.isOnline }
        if (initialPlayers.isEmpty()) return
        val spawnProtSeconds = template.spawnProtectionSecondsOverride ?: spawnProtectionSeconds
        val maxAttempts = template.maxWorldGenAttempts.coerceAtLeast(1)
        val id = idSeq.getAndIncrement()
        fun attempt(attemptNum: Int) {
            val currentPlayers = initialPlayers.filter { it.isOnline && !inRaid(it.uniqueId) }
            if (currentPlayers.isEmpty()) return
            if (attemptNum >= maxAttempts) { currentPlayers.forEach { it.sendMessage("§cWorld gen failed after $maxAttempts attempts for template ${template.displayName}") }; return }
            val w = worldFactory.create(template, id) ?: run { Bukkit.getScheduler().runTaskLater(plugin, Runnable { attempt(attemptNum+1) }, 1L); return }
            val centerMaybe = determineRaidCenter(w, template)
            if (template.id.equals("end_small_pvp", true) && centerMaybe == null) {
                // Enforce strict avoidance of center island: retry world
                Bukkit.unloadWorld(w,false); deleteWorldFolder(w.name); Bukkit.getScheduler().runTaskLater(plugin, Runnable { attempt(attemptNum+1) }, 1L); return
            }
            val center = centerMaybe ?: Location(w,0.0,0.0,0.0)
            val borderBlocks = (template.sizeChunks * 16).toDouble(); w.worldBorder.size = borderBlocks; w.worldBorder.center = center
            val acceptableTerrain = if (template.environment == World.Environment.NORMAL) !isOceanHeavy(w, borderBlocks/2.0) else true
            val structuresOk = checkTemplateStructures(w, template, borderBlocks)
            if (!acceptableTerrain || !structuresOk) { Bukkit.unloadWorld(w,false); deleteWorldFolder(w.name); Bukkit.getScheduler().runTaskLater(plugin, Runnable { attempt(attemptNum+1) }, 1L); return }
            if (template.forceNight && w.environment == World.Environment.NORMAL) { w.time = 14000; w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false) }
            if (template.requireDragon && w.environment == World.Environment.THE_END) if (w.entities.none { it.type==org.bukkit.entity.EntityType.ENDER_DRAGON }) w.spawnEntity(w.spawnLocation, org.bukkit.entity.EntityType.ENDER_DRAGON)
            val half = (template.sizeChunks * 16).toDouble()/2.0 - 4
            val extractionLoc = findExtractionPoint(w, half, Location(w, center.x, w.maxHeight.toDouble(), center.z), centerX = center.x, centerZ = center.z); clearExtractionCanopy(extractionLoc)
            val exVec = extractionLoc.toVector().subtract(center.toVector()); val dir = if (exVec.lengthSquared()==0.0) org.bukkit.util.Vector(1,0,0) else exVec.clone(); val opp = dir.normalize().multiply(-(half-8));
            val spawnBaseX = center.x + opp.x; val spawnBaseZ = center.z + opp.z
            val spawnCenterSurfaceY = getSpawnSurfaceY(w, spawnBaseX.toInt(), spawnBaseZ.toInt())
            val spawnCenter = Location(w, spawnBaseX, spawnCenterSurfaceY.toDouble(), spawnBaseZ)
            val spawns = currentPlayers.associateWith { randomSafeSpawnNear(w, spawnCenter, 32.0) }
            val now = System.currentTimeMillis()/1000L; val protectionEnds = now + spawnProtSeconds
            val durationSeconds = template.durationMinutes * 60
            val extractionOpenAfter = template.extractionOpenAfterSeconds ?: -1
            val extractionAvailableAt = if (template.unlockCondition == ExtractionUnlockCondition.TIME) protectionEnds + extractionOpenAfter else Long.MAX_VALUE
            val extractionRadius = template.extractionRadiusOverride ?: config.raidExtractionRadius
            val channelSeconds = template.channelSecondsOverride ?: config.raidExtractionChannelSeconds
            val bossBar = if (config.commRaidBossBar) chooseBossBar() else Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID).apply { isVisible=false }
            val session = RaidSession(id, w, currentPlayers.map { it.uniqueId }.toMutableSet(), RaidState.ACTIVE, protectionEnds, durationSeconds, extractionAvailableAt, extractionLoc, extractionRadius, channelSeconds, bossBar, spawnProtectionEndsAt = protectionEnds, bossBarBaseColor = bossBar.color, templateId = template.id, unlockCondition = template.unlockCondition)
            sessions[id] = session
            currentPlayers.forEach { p -> playerSession[p.uniqueId]=id; session.perPlayerChannelRemaining[p.uniqueId]=channelSeconds; bossBar.addPlayer(p); p.teleport(spawns[p]!!); giveCompass(p, session); p.sendMessage("§7Spawn protection §a${spawnProtSeconds}s") }
            log.info("Started raid session", "id" to id, "template" to template.id, "players" to currentPlayers.size, "attempt" to (attemptNum+1), "centerX" to center.blockX, "centerY" to center.blockY, "centerZ" to center.blockZ)
        }
        // Kick off first attempt next tick to avoid doing heavy gen in same tick as command execution
        Bukkit.getScheduler().runTask(plugin, Runnable { attempt(0) })
    }

    // Optimized path support
    private data class PrepContext(
        val id: Long,
        val template: org.kami.exfilCraft.raid.RaidTemplate,
        val players: List<Player>,
        val world: World,
        val requiredStructures: List<String>,
        var idx: Int = 0,
        val results: MutableMap<String, Location?> = mutableMapOf(),
        var canceled: Boolean = false
    )
    private fun needsOptimizedPath(t: org.kami.exfilCraft.raid.RaidTemplate): Boolean = t.requireCenterStructure || t.requiredStructures.isNotEmpty() || t.centerRequiredStructures.isNotEmpty() || t.alwaysEndCities
    private fun startOptimizedSession(players: List<Player>, template: org.kami.exfilCraft.raid.RaidTemplate) {
        val live = players.filter { it.isOnline }
        if (live.isEmpty()) return
        val id = idSeq.getAndIncrement()
        val world = worldFactory.create(template, id) ?: run { live.forEach { it.sendMessage("§cWorld creation failed") }; return }
        val req = LinkedHashSet<String>().apply { addAll(template.requiredStructures); addAll(template.centerRequiredStructures); if (template.alwaysEndCities) add("END_CITY") }
        val ctx = PrepContext(id, template, live, world, req.toList())
        live.forEach { it.sendMessage("§7Preparing raid world (structures)...") }
        if (ctx.requiredStructures.isEmpty()) finalizeOptimized(ctx, centerOverride = null) else seekNextStructure(ctx)
    }
    private fun seekNextStructure(ctx: PrepContext) {
        if (ctx.canceled) return
        if (ctx.idx >= ctx.requiredStructures.size) { finalizeOptimized(ctx, chooseCenter(ctx)) ; return }
        val key = ctx.requiredStructures[ctx.idx]
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val type = resolveStructureType(key)
            val center = Location(ctx.world,0.0, ctx.world.maxHeight*0.5,0.0)
            val radiusBlocks = (ctx.template.sizeChunks * 16 * 3).coerceAtMost(4096)
            val found = if (type!=null) runCatching { ctx.world.locateNearestStructure(center, type, radiusBlocks, false) }.getOrNull() else null
            ctx.results[key] = found
            ctx.players.forEach { it.sendMessage("§7${ctx.idx+1}/${ctx.requiredStructures.size} §f$key: ${if(found!=null) "§aok" else "§c-"}") }
            ctx.idx++
            seekNextStructure(ctx)
        })
    }
    private fun chooseCenter(ctx: PrepContext): Location? {
        val tpl = ctx.template
        val world = ctx.world
        val centerSpec = tpl.centerRequiredStructures.mapNotNull { ctx.results[it] }
        if (centerSpec.isNotEmpty()) return centerSpec.first()
        val found = ctx.results.values.filterNotNull()
        if (found.isEmpty()) return null
        val avgX = found.map { it.x }.average(); val avgZ = found.map { it.z }.average(); val y = world.getHighestBlockYAt(avgX.toInt(), avgZ.toInt()).toDouble()
        return Location(world, avgX, y, avgZ)
    }
    private fun finalizeOptimized(ctx: PrepContext, centerOverride: Location?) {
        if (ctx.canceled) return
        val tpl = ctx.template; val w = ctx.world
        val determined = determineRaidCenter(w, tpl)
        if (tpl.id.equals("end_small_pvp", true) && determined == null && centerOverride == null) {
            // Cannot accept center island; cancel and regenerate via retry logic outside (simpler: mark canceled & inform players)
            ctx.players.forEach { it.sendMessage("§cRetrying world generation to avoid End center island...") }
            // Clean up world
            Bukkit.unloadWorld(w,false); deleteWorldFolder(w.name)
            // Restart session creation fresh
            startOptimizedSession(ctx.players, tpl)
            ctx.canceled = true
            return
        }
        val center = centerOverride ?: determined ?: Location(w,0.0,0.0,0.0)
        val borderBlocks = (tpl.sizeChunks * 16).toDouble(); val half = borderBlocks/2.0 - 4
        w.worldBorder.size = borderBlocks; w.worldBorder.center = center
        val extractionLoc = findExtractionPoint(w, half, Location(w, center.x, w.maxHeight.toDouble(), center.z), centerX = center.x, centerZ = center.z).also { clearExtractionCanopy(it) }
        val exVec = extractionLoc.toVector().subtract(center.toVector()); val dir = if (exVec.lengthSquared()==0.0) org.bukkit.util.Vector(1,0,0) else exVec.clone(); val opp = dir.normalize().multiply(-(half-8))
        val spawnBaseX = center.x + opp.x; val spawnBaseZ = center.z + opp.z
        val spawnCenterSurfaceY = getSpawnSurfaceY(w, spawnBaseX.toInt(), spawnBaseZ.toInt())
        val spawnCenter = Location(w, spawnBaseX, spawnCenterSurfaceY.toDouble(), spawnBaseZ)
        // light chunk warm-up
        val chunks = HashSet<Pair<Int,Int>>()
        fun add(cx:Int,cz:Int){ chunks+= cx to cz }
        listOf(center, extractionLoc, spawnCenter).forEach { loc -> val cbx = loc.blockX shr 4; val cbz=loc.blockZ shr 4; for(dx in -1..1) for(dz in -1..1) add(cbx+dx, cbz+dz) }
        val it = chunks.iterator()
        fun loadStep() {
            var c=0; while(it.hasNext() && c<6){ val (cx,cz)=it.next(); w.getChunkAt(cx,cz); c++ }
            if (it.hasNext()) Bukkit.getScheduler().runTask(plugin, Runnable { loadStep() }) else Bukkit.getScheduler().runTask(plugin, Runnable { completeOptimized(ctx, center, spawnCenter, extractionLoc) })
        }
        loadStep()
    }
    private fun completeOptimized(ctx: PrepContext, center: Location, spawnCenter: Location, extractionLoc: Location) {
        if (ctx.canceled) return
        val tpl = ctx.template; val w = ctx.world; val spawnProtSeconds = tpl.spawnProtectionSecondsOverride ?: spawnProtectionSeconds
        if (tpl.forceNight && w.environment == World.Environment.NORMAL) { w.time=14000; w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false) }
        if (tpl.requireDragon && w.environment == World.Environment.THE_END) if (w.entities.none{it.type==org.bukkit.entity.EntityType.ENDER_DRAGON}) w.spawnEntity(w.spawnLocation, org.bukkit.entity.EntityType.ENDER_DRAGON)
        val spawns = ctx.players.associateWith { randomSafeSpawnNear(w, spawnCenter, 32.0) }
        val now = System.currentTimeMillis()/1000L; val protectionEnds = now + spawnProtSeconds
        val durationSeconds = tpl.durationMinutes * 60
        val extractionOpenAfter = tpl.extractionOpenAfterSeconds ?: -1
        val extractionAvailableAt = if (tpl.unlockCondition == ExtractionUnlockCondition.TIME) protectionEnds + extractionOpenAfter else Long.MAX_VALUE
        val extractionRadius = tpl.extractionRadiusOverride ?: config.raidExtractionRadius
        val channelSeconds = tpl.channelSecondsOverride ?: config.raidExtractionChannelSeconds
        val bossBar = if (config.commRaidBossBar) chooseBossBar() else Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID).apply { isVisible=false }
        val session = RaidSession(ctx.id, w, ctx.players.map { it.uniqueId }.toMutableSet(), RaidState.ACTIVE, protectionEnds, durationSeconds, extractionAvailableAt, extractionLoc, extractionRadius, channelSeconds, bossBar, spawnProtectionEndsAt = protectionEnds, bossBarBaseColor = bossBar.color, templateId = tpl.id, unlockCondition = tpl.unlockCondition)
        sessions[ctx.id] = session
        ctx.players.forEach { p -> if(!p.isOnline) return@forEach; playerSession[p.uniqueId]=ctx.id; session.perPlayerChannelRemaining[p.uniqueId]=channelSeconds; bossBar.addPlayer(p); p.teleport(spawns[p]!!); giveCompass(p, session); p.sendMessage("§7Spawn protection §a${spawnProtSeconds}s") }
        log.info("Started raid session (opt)", "id" to ctx.id, "template" to tpl.id, "players" to ctx.players.size)
    }
    // Center-aware variants
    private fun findExtractionPoint(world: World, borderHalf: Double, ref: Location, centerX: Double, centerZ: Double): Location {
        val step = 8.0; val perimeter = mutableListOf<Pair<Int,Int>>()
        var x=-borderHalf; while(x<=borderHalf){ perimeter+=(centerX+x).toInt() to (centerZ-borderHalf).toInt(); perimeter+=(centerX+x).toInt() to (centerZ+borderHalf).toInt(); x+=step }
        var z=-borderHalf; while(z<=borderHalf){ perimeter+=(centerX-borderHalf).toInt() to (centerZ+z).toInt(); perimeter+=(centerX+borderHalf).toInt() to (centerZ+z).toInt(); z+=step }
        val seen=HashSet<Pair<Int,Int>>(); val candidates=mutableListOf<Location>()
        for((cx,cz) in perimeter) if(seen.add(cx to cz)) highestStableGround(world,cx,cz)?.let{ candidates+=it.add(0.5,1.0,0.5)}
        val chosen = candidates.maxByOrNull { it.distanceSquared(ref) }; if(chosen!=null) return chosen
        val relaxed = perimeter.mapNotNull{(cx,cz)-> if(seen.add(cx to cz)) relaxedBeachGround(world,cx,cz) else null}.maxByOrNull{it.distanceSquared(ref)}; if(relaxed!=null) return relaxed.add(0.5,1.0,0.5)
        return world.spawnLocation.add(0.5,0.0,0.5)
    }

    // Wrapper deciding path
    private fun startSession(players: List<Player>, template: org.kami.exfilCraft.raid.RaidTemplate) {
        if (needsOptimizedPath(template)) startOptimizedSession(players, template) else legacyStartSession(players, template)
    }

    // --- Remaining original functionality (restored) ---
    private fun startHeartbeat() { taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, { tick() }, 20L, 20L) }
    private fun tick() {
        val now = System.currentTimeMillis()/1000L
        if (queue.isNotEmpty()) {
            val needed = config.raidMinPlayers
            val txt = if (queueCountdownStart != null) {
                val remaining = (config.raidQueueCountdownSeconds - (now - (queueCountdownStart ?: now))).coerceAtLeast(0)
                "§eQueue: ${queue.size}/$needed §7| Starting in §c${remaining}s"
            } else "§eQueue: ${queue.size}/$needed §7waiting..."
            queue.forEach { sendActionBar(it, txt) }
        }
        val toEnd = mutableListOf<RaidSession>()
        for (entry in sessions.entries) {
            val session = entry.value
            if (session.state != RaidState.ACTIVE) continue
            val rem = session.remainingSeconds(now)
            if (config.commRaidBossBar) {
                val progress = 1.0 - (rem.toDouble() / session.durationSeconds.toDouble())
                session.bossBar.progress = progress.coerceIn(0.0,1.0)
                if (rem <= 10 && session.bossBar.color != BarColor.RED) session.bossBar.color = BarColor.RED
                if (rem > 10 && session.bossBar.color != session.bossBarBaseColor) session.bossBar.color = session.bossBarBaseColor
                val timeColor = if (rem <= 10) "§c§l" else "§f"
                session.bossBar.setTitle(config.raidBossBarTitle + " $timeColor${formatTime(rem)}")
            }
            if (now < session.spawnProtectionEndsAt) {
                val left = (session.spawnProtectionEndsAt - now).toInt()
                session.players.forEach { uuid -> Bukkit.getPlayer(uuid)?.spigot()?.sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§aProtection ${left}s")) }
                continue
            }
            if (session.isExtractionUnlocked(now)) {
                if (!session.extractionAnnounced && config.commRaidExtractionActiveChat) { session.extractionAnnounced = true; broadcast(session, config.message("messages.raid.extractionActive", "&aExtraction point is now ACTIVE!").color()) }
                spawnExtractionParticles(session)
                for (uuid in session.activePlayers()) {
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (p.world != session.world) continue
                    val dist = p.location.distance(session.extractionPoint)
                    val required = session.channelSecondsRequired
                    if (dist <= session.extractionRadius) {
                        val left = (session.perPlayerChannelRemaining[uuid] ?: required) - 1
                        session.perPlayerChannelRemaining[uuid] = left
                        val prev = extractionLastSecondShown[uuid]
                        if (left >=0 && prev != left) { extractionLastSecondShown[uuid] = left; p.sendTitle("§aExtracting", "§f${left.coerceAtLeast(0)}s", 0,20,0) }
                        if (left <= 0) handleExtraction(p, session)
                    } else {
                        session.perPlayerChannelRemaining[uuid] = required
                        extractionLastSecondShown.remove(uuid)
                    }
                }
            }
            val finalPhaseThreshold = (session.durationSeconds * 0.10).toInt()
            if (!session.finalPhaseAnnounced && rem <= finalPhaseThreshold) { session.finalPhaseAnnounced = true; if (config.commRaidFinalPhaseChat) broadcast(session, config.message("messages.raid.finalPhase", "&cFINAL PHASE! {remaining} left").replace("{remaining}", formatTime(rem)).color()) }
            if (rem <= 0 || session.isComplete()) toEnd += session
        }
        toEnd.forEach { endSession(it, timeout = it.remainingSeconds(now) <= 0) }
    }
    private fun String.color(): String = replace('&','§')
    private fun broadcast(session: RaidSession, msg: String) { session.players.forEach { Bukkit.getPlayer(it)?.sendMessage(msg) } }
    private fun spawnExtractionParticles(session: RaidSession) { val w = session.world; val c = session.extractionPoint.clone().add(0.5,1.5,0.5); val particle = runCatching { Particle.valueOf(config.raidExtractionParticle.uppercase()) }.getOrElse { Particle.HAPPY_VILLAGER }; w.spawnParticle(particle, c, 80, session.extractionRadius/2,0.8, session.extractionRadius/2,0.05) }
    private fun handleExtraction(player: Player, session: RaidSession) {
        if (session.extracted.contains(player.uniqueId)) return
        session.extracted += player.uniqueId
        player.sendMessage("§aExtraction successful!")
        lastRaidOutcome[player.uniqueId] = "§aRaid #${session.id} extracted successfully. Loot secured."
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId); if (bunker != null) bunkers.teleportToBunker(player, bunker) else player.teleport(player.server.worlds.first().spawnLocation)
        session.bossBar.removePlayer(player)
        playerSession.remove(player.uniqueId)
        checkSessionCompletion(session)
    }
    fun handlePlayerDeath(player: Player) { val session = getSessionFor(player.uniqueId) ?: return; if (session.dead.contains(player.uniqueId)) return; session.dead += player.uniqueId; lastRaidOutcome[player.uniqueId] = "§cYou died in Raid #${session.id}. All carried items lost."; session.bossBar.removePlayer(player); playerSession.remove(player.uniqueId); checkSessionCompletion(session) }
    fun handlePlayerQuit(player: Player) { val session = getSessionFor(player.uniqueId) ?: return; val now = System.currentTimeMillis()/1000L; val loc = player.location.clone(); val items = (player.inventory.contents.filterNotNull() + player.inventory.armorContents.filterNotNull() + listOf(player.inventory.itemInOffHand).filter { it.type != Material.AIR }).map { it.clone() }; session.disconnectInfo[player.uniqueId] = DCInfo(loc, now, forfeited=false, items=items); session.bossBar.removePlayer(player) }
    fun giveCompassForReconnect(player: Player, session: RaidSession) { player.compassTarget = session.extractionPoint }
    fun handleRespawn(player: Player) {
        val outcome = lastRaidOutcome.remove(player.uniqueId)
        val session = getSessionFor(player.uniqueId)
        if (session != null && session.dead.contains(player.uniqueId)) { val bunker = bunkers.getBunkerForPlayer(player.uniqueId); if (bunker != null) bunkers.teleportToBunker(player, bunker) else player.teleport(player.server.worlds.first().spawnLocation); outcome?.let { player.sendMessage(it) }; return }
        if (session != null) {
            val dc = session.disconnectInfo[player.uniqueId]
            if (dc != null && !dc.forfeited) {
                val now = System.currentTimeMillis()/1000L
                val absence = now - dc.leftAt
                val threshold = (session.durationSeconds * 0.10).toLong()
                if (absence < threshold && session.state == RaidState.ACTIVE) {
                    player.teleport(dc.loc.clone().add(0.0,0.5,0.0))
                    giveCompassForReconnect(player, session)
                    player.sendMessage("§aRejoined Raid #${session.id}. Absent ${absence}s")
                    return
                } else {
                    dc.forfeited = true; player.inventory.clear(); lastRaidOutcome[player.uniqueId] = "§cForfeited Raid #${session.id} due to extended disconnect. Loot dropped.".also { player.sendMessage(it) }
                }
            }
        }
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId); if (bunker != null) bunkers.teleportToBunker(player, bunker) else player.teleport(player.server.worlds.first().spawnLocation); outcome?.let { player.sendMessage(it) }
    }
    private fun checkSessionCompletion(session: RaidSession) { if (session.activePlayers().isEmpty() && session.participantsRemaining(System.currentTimeMillis()/1000L) == 0) endSession(session, timeout=false) }
    private fun endSession(session: RaidSession, timeout: Boolean) {
        if (session.state == RaidState.ENDED) return
        session.state = RaidState.ENDED
        if (timeout) session.activePlayers().forEach { Bukkit.getPlayer(it)?.let { p -> p.sendMessage("§cRaid failed (time)."); p.health = 0.0 } }
        session.bossBar.players.forEach { session.bossBar.removePlayer(it) }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { cleanupWorld(session) }, (config.raidCleanupDelaySeconds * 20L).coerceAtLeast(20L))
        sessions.remove(session.id)
        session.players.forEach { playerSession.remove(it) }
        for (dc in session.disconnectInfo.values) { dc.forfeited = true }
        log.info("Ended raid session", "id" to session.id, "timeout" to timeout)
    }
    private fun cleanupWorld(session: RaidSession) { val world = session.world; world.players.forEach { it.teleport(it.server.worlds.first().spawnLocation) }; if (config.raidCleanupDeleteWorldOnEnd) { val name = world.name; Bukkit.unloadWorld(world,false); deleteWorldFolder(name) } }
    private fun deleteWorldFolder(name: String) { val folder = plugin.server.worldContainer.resolve(name); if (!folder.exists()) return; folder.walkBottomUp().forEach { it.delete() } }
    fun shutdown() { if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId); val copy = sessions.entries.map { it.value }; copy.forEach { endSession(it, timeout=false) } }
    fun status(player: Player) { val session = getSessionFor(player.uniqueId); if (session == null) { player.sendMessage("§7Not in a raid."); return }; val now = System.currentTimeMillis()/1000L; val rem = session.remainingSeconds(now); val extractionUnlocked = session.isExtractionUnlocked(now); val channelLeft = session.perPlayerChannelRemaining[player.uniqueId]; player.sendMessage("§aRaid #${session.id} §7State: ${session.state} Remaining: ${formatTime(rem)} ExtractionUnlocked=$extractionUnlocked ChannelLeft=${channelLeft ?: '-'}") }
    private fun formatTime(sec: Int): String { val m = sec / 60; val s = sec % 60; return String.format("%02d:%02d", m, s) }
    private fun giveCompass(p: Player, session: RaidSession) {
        val compass = ItemStack(Material.COMPASS)
        val meta = compass.itemMeta
        if (meta is CompassMeta) { meta.isLodestoneTracked = false; meta.lodestone = session.extractionPoint.block.location; meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES); meta.setDisplayName("§aExtraction Compass"); meta.lore = listOf("§7Points toward extraction"); compass.itemMeta = meta }
        else if (meta != null) { meta.setDisplayName("§aExtraction Compass"); meta.lore = listOf("§7Points toward extraction"); compass.itemMeta = meta }
        val existing = p.inventory.contents.firstOrNull { it != null && it.type == Material.COMPASS && it.itemMeta?.displayName == "§aExtraction Compass" }
        if (existing == null) p.inventory.addItem(compass) else existing.itemMeta = compass.itemMeta
        p.compassTarget = session.extractionPoint
    }
    private fun clearExtractionCanopy(loc: Location) { val w = loc.world ?: return; for (dx in -2..2) for (dz in -2..2) for (dy in 1..4) { val b = w.getBlockAt(loc.blockX+dx, loc.blockY+dy, loc.blockZ+dz); if (b.type.name.contains("LEAVES") || b.type.name.contains("LOG")) b.type = Material.AIR } }
    fun openTemplateMenu(player: Player) { openTemplateMenu(player, 0) }
    private val navPrevKey = NamespacedKey(plugin, "raid_template_nav_prev")
    private val navNextKey = NamespacedKey(plugin, "raid_template_nav_next")
    private val playerPage = mutableMapOf<UUID, Int>()
    fun openTemplateMenu(player: Player, page: Int) {
        val templates = config.raidTemplates()
        val pageSize = 9*6
        val totalPages = if (templates.isEmpty()) 1 else ((templates.size - 1) / (pageSize - 9) + 1)
        val clamped = page.coerceIn(0, totalPages-1)
        playerPage[player.uniqueId] = clamped
        val rows = 6.coerceAtMost(((templates.size - 1)/9)+1).coerceAtLeast(1)
        val inv = Bukkit.createInventory(null, rows*9, menuTitle)
        val usableSlots = if (totalPages>1) (rows-1)*9 else rows*9
        val startIndex = clamped * usableSlots
        val endIndex = (startIndex + usableSlots).coerceAtMost(templates.size)
        templates.subList(startIndex, endIndex).forEachIndexed { idx, t ->
            val qList = templateQueuesTeams[t.id]
            val queuedPlayers = qList?.sumOf { it.members.size } ?: 0
            val queuedTeams = qList?.size ?: 0
            val baseMat = (t.displayItem ?: t.iconMaterial) ?: when (t.environment) {
                World.Environment.NETHER -> Material.NETHER_STAR
                World.Environment.THE_END -> Material.CHORUS_FRUIT
                else -> if (t.requireCenterStructure || t.requiredStructures.isNotEmpty()) Material.STRUCTURE_BLOCK else Material.GRASS_BLOCK
            }
            val item = ItemStack(baseMat)
            item.itemMeta = item.itemMeta?.apply {
                setDisplayName("§a${t.displayName}")
                val lore = mutableListOf<String>()
                lore += "§7Size: §f${t.sizeChunks}x${t.sizeChunks} chunks"
                lore += "§7Teams: §f${t.minTeams}-${if (t.maxTeams==Int.MAX_VALUE) "∞" else t.maxTeams}" + " §7Players/team: §f${if (t.maxPlayersPerTeam==Int.MAX_VALUE) "∞" else t.maxPlayersPerTeam}"
                lore += "§7Players: §f${t.minPlayers}-${t.maxPlayers}"
                lore += "§7Duration: §f${t.durationMinutes}m"
                lore += if (t.unlockCondition == ExtractionUnlockCondition.TIME) "§7Extract opens: §f${((t.extractionOpenAfterSeconds?:0)+59)/60}m" else "§7Extract: §fDragon Kill"
                t.extractionRadiusOverride?.let { lore += "§7Extract Radius: §f$it" }
                t.channelSecondsOverride?.let { lore += "§7Channel: §f${it}s" }
                t.spawnProtectionSecondsOverride?.let { lore += "§7Spawn Prot: §f${it}s" }
                if (t.centerRequiredStructures.isNotEmpty()) lore += "§7Center: §f${t.centerRequiredStructures.joinToString("/")}" else if (t.requireCenterStructure) lore += "§7Center Structure Required"
                if (t.requiredStructures.isNotEmpty()) lore += "§7Needs: §f${t.requiredStructures.joinToString()}"
                if (t.requireDragon) lore += "§cDragon Present"
                if (t.alwaysEndCities) lore += "§dEnd Cities"
                lore += "§eQueue: §f${queuedPlayers}p/${t.minPlayers}p §7(${queuedTeams}t/${t.minTeams}t)"
                this.lore = lore
                persistentDataContainer.set(NamespacedKey(plugin, "raid_template_id"), org.bukkit.persistence.PersistentDataType.STRING, t.id)
            }
            inv.setItem(idx, item)
        }
        if (totalPages>1) {
            val navRowStart = (rows-1)*9
            if (clamped>0) {
                val prev = ItemStack(Material.ARROW).apply { itemMeta = itemMeta?.apply { setDisplayName("§ePrevious Page"); persistentDataContainer.set(navPrevKey, org.bukkit.persistence.PersistentDataType.INTEGER, 1) } }
                inv.setItem(navRowStart, prev)
            }
            val pageInfo = ItemStack(Material.PAPER).apply { itemMeta = itemMeta?.apply { setDisplayName("§bPage ${clamped+1}/$totalPages") } }
            inv.setItem(navRowStart+4, pageInfo)
            if (clamped < totalPages-1) {
                val next = ItemStack(Material.ARROW).apply { itemMeta = itemMeta?.apply { setDisplayName("§eNext Page"); persistentDataContainer.set(navNextKey, org.bukkit.persistence.PersistentDataType.INTEGER, 1) } }
                inv.setItem(navRowStart+8, next)
            }
        }
        player.openInventory(inv)
    }
    fun handleTemplateClick(player: Player, templateId: String) {
        val template = config.raidTemplates().find { it.id == templateId } ?: run { player.sendMessage("§cTemplate not found."); return }
        if (inRaid(player.uniqueId)) { player.sendMessage("§cAlready in raid."); return }
        if (isQueued(player.uniqueId)) { player.sendMessage("§eAlready queued. Use /raid queue leave to leave."); return }
        val team = teams.getTeam(player.uniqueId)
        val members = (team?.members ?: setOf(player.uniqueId)).filter { Bukkit.getPlayer(it)?.isOnline == true && !inRaid(it) && !isQueued(it) }.toSet()
        if (members.isEmpty()) { player.sendMessage("§cNo eligible members to queue."); return }
        if (members.size > template.maxPlayersPerTeam) { player.sendMessage("§cTeam size ${members.size} exceeds allowed per-team max ${template.maxPlayersPerTeam} for this template."); return }
        val qList = templateQueuesTeams.getOrPut(template.id) { mutableListOf() }
        if (qList.any { it.members.any(members::contains) }) { player.sendMessage("§eAlready queued."); return }
        val queuedTeam = QueuedTeam(team?.leader ?: player.uniqueId, members)
        qList += queuedTeam
        val playerCount = qList.sumOf { it.members.size }
        val teamCount = qList.size
        val needPlayers = (template.minPlayers - playerCount).coerceAtLeast(0)
        val needTeams = (template.minTeams - teamCount).coerceAtLeast(0)
        player.sendMessage("§aQueued ${if (members.size==1) "you" else "team (${members.size})"} for ${template.displayName} §7[Queue: ${playerCount}p/${template.minPlayers}p ${teamCount}t/${template.minTeams}t]")
        if (needPlayers>0 || needTeams>0) player.sendMessage("§7Need §e${needPlayers}§7 more players and §e${needTeams}§7 more teams to start (whichever applies).")
        updateTemplateQueues()
    }
    private fun refreshOpenTemplateMenus() {
        for (p in Bukkit.getOnlinePlayers()) {
            val open = p.openInventory?.topInventory ?: continue
            if (p.openInventory.title == menuTitle) {
                val page = playerPage[p.uniqueId] ?: 0
                Bukkit.getScheduler().runTask(plugin, Runnable { openTemplateMenu(p, page) })
            }
        }
    }
    private fun updateTemplateQueues() {
        val templates = config.raidTemplates().associateBy { it.id }
        for ((tid, list) in templateQueuesTeams) {
            val t = templates[tid] ?: continue
            var totalPlayers = 0
            val chosen = mutableListOf<QueuedTeam>()
            for (qt in list) {
                if (totalPlayers + qt.members.size > t.maxPlayers) continue
                if (qt.members.size > t.maxPlayersPerTeam) continue
                chosen += qt
                totalPlayers += qt.members.size
                if (totalPlayers >= t.maxPlayers) break
            }
            val teamCount = chosen.size
            if (teamCount >= t.minTeams && teamCount <= t.maxTeams && totalPlayers >= t.minPlayers) {
                val players = chosen.flatMap { it.members }.mapNotNull { Bukkit.getPlayer(it) }
                list.removeAll(chosen.toSet())
                startSession(players, t)
            }
        }
        refreshOpenTemplateMenus()
    }
    private fun startSession(players: List<Player>) { val template = config.raidTemplates().firstOrNull() ?: return; startSession(players, template) }
    fun getSessionByWorld(world: World): RaidSession? = sessions.entries.firstOrNull { it.value.world == world && it.value.state == RaidState.ACTIVE }?.value
    fun handleDragonDeath(world: World) { val session = getSessionByWorld(world) ?: return; if (session.unlockCondition == ExtractionUnlockCondition.DRAGON_DEFEATED && !session.conditionalUnlocked) { session.conditionalUnlocked = true; session.extractionAvailableAt = System.currentTimeMillis()/1000L; broadcast(session, "§aDragon defeated! Extraction now ACTIVE.") } }
    private var cachedStructureTypes: List<org.bukkit.StructureType>? = null
    private fun allStructureTypes(): List<org.bukkit.StructureType> {
        cachedStructureTypes?.let { return it }
        val list = mutableListOf<org.bukkit.StructureType>()
        try { val m = org.bukkit.StructureType::class.java.getMethod("values"); val arr = m.invoke(null) as? Array<*>?; if (arr != null) arr.filterIsInstance<org.bukkit.StructureType>().forEach { list += it } } catch (_: Throwable) {}
        if (list.isEmpty()) { for (f in org.bukkit.StructureType::class.java.declaredFields) { try { if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue; f.isAccessible = true; val v = f.get(null); if (v is org.bukkit.StructureType) list += v } catch (_: Throwable) {} } }
        cachedStructureTypes = list
        return list
    }
    private fun resolveStructureType(name: String): org.bukkit.StructureType? { val up = name.lowercase(Locale.ROOT); return allStructureTypes().firstOrNull { it.name.lowercase(Locale.ROOT) == up || it.key.key.lowercase(Locale.ROOT) == up } }
    private fun checkTemplateStructures(world: World, template: org.kami.exfilCraft.raid.RaidTemplate, border: Double): Boolean {
        if (!template.requireCenterStructure && template.requiredStructures.isEmpty() && !template.alwaysEndCities && template.centerRequiredStructures.isEmpty()) return true
        val center = Location(world,0.0, world.maxHeight/2.0,0.0)
        val searchRadiusBlocks = (border/2).toInt()
        val neededAnywhere = mutableListOf<String>()
        neededAnywhere += template.requiredStructures
        if (template.alwaysEndCities && !neededAnywhere.contains("END_CITY")) neededAnywhere += "END_CITY"
        for (raw in neededAnywhere) {
            val type = resolveStructureType(raw) ?: return false
            val found = try { world.locateNearestStructure(center, type, searchRadiusBlocks, false) } catch (t: Throwable) { null }
            if (found == null) return false
        }
        if (template.requireCenterStructure || template.centerRequiredStructures.isNotEmpty()) {
            val centerRadiusBlocks = (template.centerRadiusChunks * 16).coerceAtLeast(16)
            val centerList = if (template.centerRequiredStructures.isNotEmpty()) template.centerRequiredStructures else neededAnywhere
            var anyFound = false
            for (raw in centerList) {
                val type = resolveStructureType(raw) ?: continue
                val found = try { world.locateNearestStructure(center, type, centerRadiusBlocks, false) } catch (_: Throwable) { null }
                if (found != null && center.distance(found) <= centerRadiusBlocks) { anyFound = true; break }
            }
            if (!anyFound) return false
        }
        return true
    }
    fun isQueued(uuid: UUID): Boolean = templateQueuesTeams.values.any { teams -> teams.any { it.members.contains(uuid) } }
    private fun findQueuedTeam(uuid: UUID): Pair<String, QueuedTeam>? { for ((tid, list) in templateQueuesTeams) { val found = list.firstOrNull { it.members.contains(uuid) }; if (found != null) return tid to found }; return null }
    fun leaveQueue(player: Player) { val found = findQueuedTeam(player.uniqueId) ?: run { player.sendMessage("§eNot queued for any template."); return }; val (tid, team) = found; templateQueuesTeams[tid]?.remove(team); player.sendMessage("§cLeft queue for template §f$tid§c (removed team of ${team.members.size})."); refreshOpenTemplateMenus() }
    fun queueStatus(player: Player) {
        if (templateQueuesTeams.isEmpty()) { player.sendMessage("§7No active template queues."); return }
        player.sendMessage("§aTemplate Queues:")
        val templates = config.raidTemplates().associateBy { it.id }
        for ((tid, list) in templateQueuesTeams) {
            val t = templates[tid]
            val playerCount = list.sumOf { it.members.size }
            val teamCount = list.size
            val needPlayers = (t?.minPlayers ?: 0)
            val needTeams = (t?.minTeams ?: 0)
            val teamDetails = list.joinToString { qt ->
                val leaderName = Bukkit.getOfflinePlayer(qt.leader).name ?: "?"
                val membersNames = qt.members.map { Bukkit.getOfflinePlayer(it).name ?: "?" }.joinToString(",")
                "$leaderName(${qt.members.size})[$membersNames]"
            }
            player.sendMessage("§7- §f$tid§7: §b${playerCount}p §7(${teamCount} teams) need ≥ ${needPlayers}p & ${needTeams} teams")
            if (teamDetails.isNotBlank()) player.sendMessage("   §8Teams: §7$teamDetails")
        }
        val available = availablePlayers().size
        player.sendMessage("§7Available (not in raid / queue): §a$available")
    }
    fun availablePlayers(): List<Player> = Bukkit.getOnlinePlayers().filter { !inRaid(it.uniqueId) && !isQueued(it.uniqueId) }
    fun adminForceStart(templateId: String?, initiator: Player): Boolean {
        val templates = config.raidTemplates()
        val template = templateId?.let { id -> templates.firstOrNull { it.id.equals(id, true) } }
            ?: templates.firstOrNull { templateQueuesTeams[it.id]?.isNotEmpty() == true }
            ?: templates.firstOrNull()
        if (template == null) { initiator.sendMessage("§cNo templates available."); return false }
        val queuedTeams = templateQueuesTeams[template.id]
        val playerUUIDs = if (queuedTeams != null && queuedTeams.isNotEmpty()) queuedTeams.flatMap { it.members } else listOf(initiator.uniqueId)
        queuedTeams?.clear()
        val onlinePlayers = playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.filter { !inRaid(it.uniqueId) }
        if (onlinePlayers.isEmpty()) { initiator.sendMessage("§cNo eligible players to start."); return false }
        initiator.sendMessage("§e[Admin] Forcing raid start template=${template.id} players=${onlinePlayers.size}")
        startSession(onlinePlayers, template)
        return true
    }
    fun adminForceEnd(target: Player?, all: Boolean, initiator: Player): Int {
        val active = sessions.values.filter { it.state == RaidState.ACTIVE }
        if (active.isEmpty()) { initiator.sendMessage("§7No active sessions."); return 0 }
        val toEnd = when {
            all -> active
            target != null -> active.filter { it.players.contains(target.uniqueId) }
            else -> emptyList()
        }
        toEnd.forEach { endSession(it, timeout=false) }
        initiator.sendMessage("§e[Admin] Ended ${toEnd.size} session(s).")
        return toEnd.size
    }
    fun adminForceExtract(target: Player, treatAsAlive: Boolean, initiator: Player): Boolean {
        val session = getSessionFor(target.uniqueId) ?: run { initiator.sendMessage("§cPlayer not in raid."); return false }
        if (treatAsAlive && session.dead.remove(target.uniqueId)) { initiator.sendMessage("§7Removed death state for ${target.name} before extraction.") }
        if (session.extracted.contains(target.uniqueId)) { initiator.sendMessage("§7Already extracted."); return false }
        session.extracted += target.uniqueId
        lastRaidOutcome[target.uniqueId] = "§aRaid #${session.id} extracted (admin). Loot secured."
        val bunker = bunkers.getBunkerForPlayer(target.uniqueId); if (bunker != null) bunkers.teleportToBunker(target, bunker) else target.teleport(target.server.worlds.first().spawnLocation)
        session.bossBar.removePlayer(target)
        playerSession.remove(target.uniqueId)
        checkSessionCompletion(session)
        initiator.sendMessage("§e[Admin] Forced extraction for ${target.name}.")
        target.sendMessage("§e[Admin] You were forcibly extracted.")
        return true
    }
    fun adminListSessions(viewer: Player) {
        val now = System.currentTimeMillis()/1000L
        val active = sessions.values.filter { it.state == RaidState.ACTIVE }
        if (active.isEmpty()) { viewer.sendMessage("§7No active sessions."); return }
        viewer.sendMessage("§aActive Raid Sessions:")
        active.sortedBy { it.id }.forEach { s -> val rem = s.remainingSeconds(now); viewer.sendMessage("§7#${s.id} tpl=${s.templateId} players=${s.activePlayers().size}/${s.players.size} rem=${formatTime(rem)} extracted=${s.extracted.size} dead=${s.dead.size}") }
    }
    fun pruneStaleWorldFolders(): Int {
        val active = Bukkit.getWorlds().map { it.name }.toSet()
        val root = plugin.server.worldContainer
        var removed = 0
        root.listFiles { f -> f.isDirectory && f.name.startsWith("exfil_raid_") }?.forEach { dir -> if (!active.contains(dir.name)) { dir.walkBottomUp().forEach { it.delete() }; removed++ } }
        if (removed>0) log.info("Pruned stale raid worlds", "removed" to removed)
        return removed
    }
    fun adminUnlockExtraction(p: Player, templateIdOrPlayer: String?): Boolean {
        val now = System.currentTimeMillis()/1000L
        val sessionsTarget = when {
            templateIdOrPlayer == null -> sessions.values.filter { it.state == RaidState.ACTIVE }
            sessions.values.any { it.templateId.equals(templateIdOrPlayer, true) } -> sessions.values.filter { it.templateId.equals(templateIdOrPlayer, true) }
            else -> {
                val pl = Bukkit.getPlayerExact(templateIdOrPlayer)
                if (pl != null) { val s = getSessionFor(pl.uniqueId); if (s!=null) listOf(s) else emptyList() } else emptyList()
            }
        }
        if (sessionsTarget.isEmpty()) { p.sendMessage("§cNo matching active session(s) for unlock."); return false }
        sessionsTarget.forEach { s -> s.extractionAvailableAt = now; s.conditionalUnlocked = true; broadcast(s, "§e[Admin] Extraction unlocked early!") }
        p.sendMessage("§aUnlocked extraction for ${sessionsTarget.size} session(s)")
        return true
    }
    fun portalExtract(player: Player): Boolean {
        val session = getSessionFor(player.uniqueId) ?: return false
        if (session.world.environment != World.Environment.THE_END) return false
        if (session.extracted.contains(player.uniqueId) || session.dead.contains(player.uniqueId)) return false
        session.extracted += player.uniqueId
        lastRaidOutcome[player.uniqueId] = "§aRaid #${session.id} extracted via End portal. Loot secured."
        val bunker = bunkers.getBunkerForPlayer(player.uniqueId)
        if (bunker != null) {
            bunkers.teleportToBunker(player, bunker)
            // Set respawn/home to bunker center
            val world = player.world
            val loc = player.location
            try { player.setBedSpawnLocation(loc, true) } catch (_: Throwable) {}
        } else player.teleport(player.server.worlds.first().spawnLocation)
        session.bossBar.removePlayer(player)
        playerSession.remove(player.uniqueId)
        checkSessionCompletion(session)
        player.sendMessage("§aPortal extraction successful!")
        return true
    }

    // --- Nether-specific ground finding ---
    private fun netherGroundY(world: World, x: Int, z: Int): Int? {
        // Scan downward from just below roof to find first solid non-bedrock block with 2 air above and no lava at/above feet.
        val maxScanStart = (world.maxHeight - 2).coerceAtMost(122)
        val minY = world.minHeight + 5
        for (y in maxScanStart downTo minY) {
            val block = world.getBlockAt(x, y, z)
            if (!block.type.isSolid) continue
            if (block.type == Material.BEDROCK) continue
            // Avoid magma / lava / fire surfaces for spawn
            if (block.type == Material.LAVA || block.type == Material.MAGMA_BLOCK || block.type == Material.FIRE) continue
            val above1 = world.getBlockAt(x, y + 1, z)
            val above2 = world.getBlockAt(x, y + 2, z)
            if (!above1.type.isAir || !above2.type.isAir) continue
            // Avoid being immediately under bedrock roof (gives claustrophobic spawn) – ensure at least 3 blocks of air upwards before bedrock
            val roofClear = (y + 3 >= world.maxHeight) || world.getBlockAt(x, y + 3, z).type != Material.BEDROCK
            if (!roofClear) continue
            // Avoid spawning over exposed lava (check one block below solid surface for lava pockets)
            val below = world.getBlockAt(x, y - 1, z)
            if (below.type == Material.LAVA) continue
            return y
        }
        return null
    }
    private fun getSpawnSurfaceY(world: World, x: Int, z: Int): Int {
        return if (world.environment == World.Environment.NETHER) netherGroundY(world, x, z) ?: world.spawnLocation.y.toInt() else world.getHighestBlockAt(x, z).y
    }
    // --- End outer island center selection ---
    private fun determineRaidCenter(world: World, template: org.kami.exfilCraft.raid.RaidTemplate): Location? {
        if (world.environment != World.Environment.THE_END) return Location(world, 0.0, 0.0, 0.0)
        if (!template.id.equals("end_small_pvp", ignoreCase = true)) return Location(world, 0.0, 0.0, 0.0)
        val rand = ThreadLocalRandom.current()
        val attempts = 320
        val radiusMin = 1300.0
        val radiusMax = 3800.0
        for (i in 0 until attempts) {
            val ang = rand.nextDouble(0.0, Math.PI * 2)
            val r = Math.sqrt(rand.nextDouble(radiusMin*radiusMin, radiusMax*radiusMax))
            val x = (Math.cos(ang) * r).toInt()
            val z = (Math.sin(ang) * r).toInt()
            world.getChunkAt(x shr 4, z shr 4)
            val top = world.getHighestBlockAt(x, z)
            val mat = top.type
            if (mat != Material.END_STONE) continue
            if (top.y > 120) continue
            var nearbyEnd = 0
            for (dx in -3..3) for (dz in -3..3) {
                if (world.getBlockAt(x+dx, top.y-1, z+dz).type == Material.END_STONE || world.getBlockAt(x+dx, top.y, z+dz).type == Material.END_STONE) nearbyEnd++
            }
            if (nearbyEnd < 12) continue
            worldGenLog.debug("Chosen outer End island center", "template" to template.id, "x" to x, "z" to z, "attempt" to (i+1))
            return Location(world, x.toDouble(), 0.0, z.toDouble())
        }
        worldGenLog.warn("No suitable End island", "template" to template.id, "attempts" to attempts)
        return null // signal failure so caller can regenerate
    }

    @Volatile private var testWorldFactoryOverride: RaidWorldFactory? = null
    internal fun setWorldFactoryForTest(factory: RaidWorldFactory) { this.worldFactory = factory }

    internal fun activeSessionCountForTest(): Int = sessions.size
    internal fun testSessions(): Collection<RaidSession> = sessions.values
    internal fun testTickExpose() = tick()
}
