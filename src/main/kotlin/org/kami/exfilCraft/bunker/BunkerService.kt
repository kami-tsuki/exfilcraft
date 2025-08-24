
package org.kami.exfilCraft.bunker

/* MIT License 2025 tsuki */

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.Particle
import org.kami.exfilCraft.core.ConfigService
import org.kami.exfilCraft.db.DatabaseService
import org.kami.exfilCraft.profile.ProfileService
import org.kami.exfilCraft.logging.LoggingService
import org.kami.exfilCraft.bunker.model.Bunker
import org.kami.exfilCraft.bunker.model.BunkerInvite
import org.kami.exfilCraft.bunker.model.BunkerMember
import org.kami.exfilCraft.core.log
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BunkerService(private val plugin: JavaPlugin, private val db: DatabaseService, private val config: ConfigService, private val profiles: ProfileService, logging: LoggingService) {

    private val log = logging.section("BUNKER")

    private val bunkerCache = ConcurrentHashMap<UUID, Bunker>()
    private data class PendingInvite(val from: UUID, val to: UUID, val bunkerId: Long, val createdAt: Long, val expiresAt: Long)
    private val invites = mutableListOf<PendingInvite>()
    private val maxMembers = 4 // owner + 3

    private fun inviteExpirySeconds(): Long = config.bunkerInviteExpiryMinutes * 60
    private fun purgeInvites(now: Long = Instant.now().epochSecond) { invites.removeIf { it.expiresAt < now } }

    fun inviteMember(inviter: UUID, target: UUID): Boolean {
        purgeInvites()
        val bunker = getBunkerForPlayer(inviter) ?: return false
        if (!isMemberOfBunker(inviter, bunker)) return false
        if (getBunkerForPlayer(target) != null) return false
        val members = getMembers(bunker)
        if (members.size >= maxMembers) return false
        invites.removeIf { it.to == target }
        val now = Instant.now().epochSecond
        invites += PendingInvite(inviter, target, bunker.id, now, now + inviteExpirySeconds())
        return true
    }

    fun acceptInvite(target: UUID, inviter: UUID): Boolean {
        purgeInvites()
        val now = Instant.now().epochSecond
        val inv = invites.firstOrNull { it.to == target && it.from == inviter && it.expiresAt >= now } ?: return false
        if (getBunkerForPlayer(target) != null) { invites.remove(inv); return false }
        val bunker = getBunkerByOwner(inviter) ?: return false
        val members = getMembers(bunker)
        if (members.size >= maxMembers) { invites.remove(inv); return false }
        db.execUpdate("INSERT OR IGNORE INTO bunker_member(bunker_id,member_uuid,role) VALUES(?,?,?)") { setLong(1, bunker.id); setString(2, target.toString()); setString(3, "MEMBER") }
        invites.remove(inv)
        return true
    }

    fun transferOwnership(currentOwner: UUID, newOwner: UUID): Boolean {
        val bunker = getBunkerByOwner(currentOwner) ?: return false
        if (currentOwner == newOwner) return false
        val members = getMembers(bunker)
        if (!members.contains(newOwner)) return false // must already be member (invited)
        // Add old owner as member
        db.execUpdate("INSERT OR IGNORE INTO bunker_member(bunker_id,member_uuid,role) VALUES(?,?,?)") { setLong(1, bunker.id); setString(2, currentOwner.toString()); setString(3, "MEMBER") }
        // Remove new owner member row if exists (becoming owner)
        db.execUpdate("DELETE FROM bunker_member WHERE bunker_id=? AND member_uuid=?") { setLong(1, bunker.id); setString(2, newOwner.toString()) }
        // Update bunker owner
        db.execUpdate("UPDATE bunker SET owner_uuid=?, updated_at=? WHERE id=?") { setString(1, newOwner.toString()); setLong(2, Instant.now().epochSecond); setLong(3, bunker.id) }
        bunkerCache.remove(currentOwner)
        bunkerCache[newOwner] = bunker.copy(ownerUuid = newOwner).also { bunkerCache[newOwner] = it }
        return true
    }

    fun removeMember(requester: UUID, target: UUID): Boolean {
        if (requester == target) return false // use leave flow later if needed
        val bunkerReq = getBunkerForPlayer(requester) ?: return false
        val bunkerTarget = getBunkerForPlayer(target) ?: return false
        if (bunkerReq.id != bunkerTarget.id) return false
        // Cannot remove owner
        if (bunkerTarget.ownerUuid == target) return false
        // Any member can remove others (except owner)
        db.execUpdate("DELETE FROM bunker_member WHERE bunker_id=? AND member_uuid=?") { setLong(1, bunkerReq.id); setString(2, target.toString()) }
        bunkerCache.remove(target)
        return true
    }

    fun getMembers(bunker: Bunker): List<UUID> {
        val owner = bunker.ownerUuid
        val rows = db.selectAll("SELECT member_uuid FROM bunker_member WHERE bunker_id=?", { setLong(1, bunker.id) }) { rs -> UUID.fromString(rs.getString("member_uuid")) }
        return (rows + owner).distinct()
    }

    fun isMemberOfBunker(player: UUID, bunker: Bunker): Boolean = getMembers(bunker).contains(player)

    fun ensureBunkerWorld(): World {
        val name = config.bunkerWorldName
        val existing = Bukkit.getWorld(name)
        if (existing != null) return existing
        val creator = WorldCreator(name)
        creator.generator(org.kami.exfilCraft.world.BunkerWorldGenerator())
        creator.environment(World.Environment.NORMAL)
        creator.type(WorldType.NORMAL)
        val world = creator.createWorld() ?: error("Failed to create bunker world")
        world.worldBorder.size = 256.0 // 256x256 per spec (16x16 chunks)
        // Gamerules / performance tweaks
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, !config.ruleDisableDaylightCycle)
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, !config.ruleDisableWeatherCycle)
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, !config.ruleDisableMobSpawns)
        world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, config.ruleRandomTickSpeed)
        return world
    }

    fun getBunkerByOwner(uuid: UUID): Bunker? {
        bunkerCache[uuid]?.let { return it }
        val b = db.selectOne("SELECT * FROM bunker WHERE owner_uuid=?", { setString(1, uuid.toString()) }) { rs ->
            Bunker(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getInt("origin_chunk_x"),
                rs.getInt("origin_chunk_z"),
                rs.getInt("start_y"),
                rs.getInt("cube_size"),
                rs.getInt("cubes_count"),
                rs.getLong("last_expansion_ts").takeIf { !rs.wasNull() },
                rs.getLong("created_at"),
                rs.getLong("updated_at")
            )
        }
        if (b != null) bunkerCache[uuid] = b
        return b
    }

    fun getBunkerForPlayer(uuid: UUID): Bunker? {
        bunkerCache[uuid]?.let { return it }
        val owner = getBunkerByOwner(uuid)
        if (owner != null) return owner
        val b = db.selectOne("SELECT b.* FROM bunker b JOIN bunker_member m ON b.id=m.bunker_id WHERE m.member_uuid=?", { setString(1, uuid.toString()) }) { rs ->
            Bunker(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getInt("origin_chunk_x"),
                rs.getInt("origin_chunk_z"),
                rs.getInt("start_y"),
                rs.getInt("cube_size"),
                rs.getInt("cubes_count"),
                rs.getLong("last_expansion_ts").takeIf { !rs.wasNull() },
                rs.getLong("created_at"),
                rs.getLong("updated_at")
            )
        }
        if (b != null) bunkerCache[uuid] = b
        return b
    }

    fun allocateIfAbsent(player: Player): Bunker {
        val existing = getBunkerForPlayer(player.uniqueId)
        if (existing != null) return existing
        // Determine origin chunk separated by min separation (simple linear strategy)
        val sep = config.bunkerMinChunkSeparation
        val index = countBunkers()
        val chunkX = index * sep // TODO: spiral for better packing
        val chunkZ = 0
        val now = Instant.now().epochSecond
        db.execUpdate("INSERT INTO bunker(owner_uuid,origin_chunk_x,origin_chunk_z,start_y,cube_size,cubes_count,last_expansion_ts,created_at,updated_at) VALUES(?,?,?,?,?,?,NULL,?,?)") {
            setString(1, player.uniqueId.toString())
            setInt(2, chunkX)
            setInt(3, chunkZ)
            setInt(4, config.bunkerStartY)
            setInt(5, config.bunkerCubeSize)
            setInt(6, 1)
            setLong(7, now)
            setLong(8, now)
        }
        val bunker = getBunkerByOwner(player.uniqueId)!!
        bunkerCache[player.uniqueId] = bunker
        // Insert initial cube mapping (0,0,0) if not present
        db.execUpdate("INSERT OR IGNORE INTO bunker_cube(bunker_id,dx,dy,dz) VALUES(?,?,?,?)") {
            setLong(1, bunker.id); setInt(2,0); setInt(3,0); setInt(4,0)
        }
        carveInitialCube(bunker)
        log.info("Allocated bunker", "player" to player.name, "chunkX" to chunkX, "chunkZ" to chunkZ, "id" to bunker.id)
        return bunker
    }

    private fun carveInitialCube(bunker: Bunker) {
        val world = ensureBunkerWorld()
        val chunkX = bunker.originChunkX
        val chunkZ = bunker.originChunkZ
        if (!world.isChunkLoaded(chunkX, chunkZ)) world.loadChunk(chunkX, chunkZ)
        val startX = chunkX * 16
        val startZ = chunkZ * 16
        val size = bunker.cubeSize
        val startY = bunker.startY
        val endY = startY + size - 1
        // Floor: fill startY layer with STONE (only inside cube area) then air above
        for (x in 0 until size) {
            for (z in 0 until size) {
                val wx = startX + x
                val wz = startZ + z
                world.getBlockAt(wx, startY, wz).type = Material.STONE
                for (y in (startY + 1)..endY) {
                    world.getBlockAt(wx, y, wz).type = Material.AIR
                }
            }
        }
    }

    private fun countBunkers(): Int = db.selectOne("SELECT COUNT(*) c FROM bunker", {}) { rs -> rs.getInt("c") } ?: 0

    fun teleportToBunker(player: Player, bunker: Bunker) {
        val world = ensureBunkerWorld()
        val centerX = bunker.originChunkX * 16 + bunker.cubeSize / 2
        val centerZ = bunker.originChunkZ * 16 + bunker.cubeSize / 2
        val y = bunker.startY + 1
        player.teleport(Location(world, centerX + 0.5, y.toDouble(), centerZ + 0.5))
        // Remove only the extraction compass (identified by display name)
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.COMPASS && item.itemMeta?.displayName == "§aExtraction Compass") {
                inv.clear(i)
                break
            }
        }
        // Ensure bunker functions as a recovery zone
        if (player.foodLevel < 20) player.foodLevel = 20
        player.saturation = 20f
    }

    fun getExpansionCooldownRemainingSeconds(bunker: Bunker): Long {
        val last = bunker.lastExpansionTs ?: return 0
        val cd = config.bunkerExpansionCooldownMinutes * 60
        val elapsed = Instant.now().epochSecond - last
        val remain = cd - elapsed
        return if (remain < 0) 0 else remain
    }

    private fun markExtended(bunker: Bunker) {
        val now = Instant.now().epochSecond
        bunker.lastExpansionTs = now
        db.execUpdate("UPDATE bunker SET last_expansion_ts=?, cubes_count=?, updated_at=? WHERE id=?") {
            setLong(1, now); setInt(2, bunker.cubesCount); setLong(3, now); setLong(4, bunker.id)
        }
    }

    fun nextExpansionCostXp(bunker: Bunker): Int = config.expansionCostBaseXp * bunker.cubesCount

    fun extendBunkerFacing(player: Player, bunker: Bunker, explicitDir: Pair<Int,Int>? = null): Boolean {
        val cooldownRemain = getExpansionCooldownRemainingSeconds(bunker)
        if (cooldownRemain > 0) {
            player.sendMessage("§cOn cooldown: ${cooldownRemain}s remaining")
            return false
        }
        val cubes = getCubes(bunker)
        val horizontalLimit = config.ruleInitialHorizontalLimit
        if (cubes.size >= horizontalLimit) {
            player.sendMessage("§cExpansion limit reached.")
            return false
        }
        val currentCube = getCubeContaining(bunker, player.location.blockX, player.location.blockY, player.location.blockZ) ?: Triple(0,0,0)
        val (baseDx, _, baseDz) = currentCube
        val (offX, offZ) = explicitDir ?: desiredDirectionFromView(player)
        val targetDx = baseDx + offX
        val targetDz = baseDz + offZ
        if (targetDx !in -1..1 || targetDz !in -1..1) {
            player.sendMessage("§cOut of bounds.")
            return false
        }
        if (cubes.any { it.first == targetDx && it.second == 0 && it.third == targetDz }) {
            player.sendMessage("§cCube already unlocked.")
            return false
        }
        val cost = nextExpansionCostXp(bunker)
        if (player.totalExperience < cost) {
            player.sendMessage("§cNeed ${cost} XP. You have ${player.totalExperience}.")
            return false
        }
        player.giveExp(-cost)
        db.execUpdate("INSERT OR IGNORE INTO bunker_cube(bunker_id,dx,dy,dz) VALUES(?,?,?,?)") {
            setLong(1, bunker.id); setInt(2, targetDx); setInt(3, 0); setInt(4, targetDz)
        }
        bunker.cubesCount += 1
        markExtended(bunker)
        bunkerCache[bunker.ownerUuid] = bunker
        carveAdditionalCube(bunker, targetDx, 0, targetDz)
        player.sendMessage("§aExtended bunker at ($targetDx,$targetDz). Cost: ${cost} XP. New size: ${bunker.cubesCount} cubes. Cooldown: ${config.bunkerExpansionCooldownMinutes}m")
        return true
    }

    fun previewCube(bunker: Bunker, dx: Int, dz: Int, player: Player) {
        val world = ensureBunkerWorld()
        val size = bunker.cubeSize
        val baseX = bunker.originChunkX * 16 + dx * size
        val baseZ = bunker.originChunkZ * 16 + dz * size
        val y = bunker.startY + 1
        val step = 1
        for (x in 0 until size step step) {
            world.spawnParticle(Particle.END_ROD, baseX + x + 0.5, y.toDouble(), baseZ + 0.5, 0)
            world.spawnParticle(Particle.END_ROD, baseX + x + 0.5, y.toDouble(), baseZ + size - 0.5, 0)
        }
        for (z in 0 until size step step) {
            world.spawnParticle(Particle.END_ROD, baseX + 0.5, y.toDouble(), baseZ + z + 0.5, 0)
            world.spawnParticle(Particle.END_ROD, baseX + size - 0.5, y.toDouble(), baseZ + z + 0.5, 0)
        }
        player.sendMessage("§7Preview shown for cube ($dx,$dz)")
    }

    fun availableAdjacentDirections(bunker: Bunker, currentDx: Int, currentDz: Int): List<Pair<Int,Int>> {
        val cubes = getCubes(bunker)
        val occupied = cubes.filter { it.second == 0 }.map { it.first to it.third }.toSet()
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        return dirs.filter { (ox, oz) ->
            val nx = currentDx + ox
            val nz = currentDz + oz
            nx in -1..1 && nz in -1..1 && (nx to nz) !in occupied
        }
    }

    fun directionName(offset: Pair<Int,Int>): String = when (offset) {
        1 to 0 -> "east"
        -1 to 0 -> "west"
        0 to 1 -> "south"
        0 to -1 -> "north"
        else -> "?"
    }

    fun parseDirectionToken(token: String): Pair<Int,Int>? = when(token.lowercase()) {
        "east","e" -> 1 to 0
        "west","w" -> -1 to 0
        "south","s" -> 0 to 1
        "north","n" -> 0 to -1
        else -> null
    }

    // --- Added helper methods ---

    /** Returns all cube offsets (dx,dy,dz) belonging to this bunker. */
    fun getCubes(bunker: Bunker): List<Triple<Int,Int,Int>> =
        db.selectAll("SELECT dx,dy,dz FROM bunker_cube WHERE bunker_id=?", { setLong(1, bunker.id) }) { rs ->
            Triple(rs.getInt("dx"), rs.getInt("dy"), rs.getInt("dz"))
        }

    /** Determine if world block coordinates fall inside any carved cube. */
    fun isInsideAnyCube(bunker: Bunker, x: Int, y: Int, z: Int): Boolean {
        if (y < bunker.startY) return false
        val size = bunker.cubeSize
        val baseX = bunker.originChunkX * 16
        val baseZ = bunker.originChunkZ * 16
        // Fetch once
        val cubes = getCubes(bunker)
        for ((dx, _, dz) in cubes) {
            val minX = baseX + dx * size
            val minZ = baseZ + dz * size
            if (x >= minX && x < minX + size && z >= minZ && z < minZ + size) return true
        }
        return false
    }

    /** Returns cube offset containing the given world block coords or null. */
    fun getCubeContaining(bunker: Bunker, x: Int, y: Int, z: Int): Triple<Int,Int,Int>? {
        val size = bunker.cubeSize
        val baseX = bunker.originChunkX * 16
        val baseZ = bunker.originChunkZ * 16
        val relY = y - bunker.startY
        if (relY < 0 || relY >= size) return null
        val relX = x - baseX
        val relZ = z - baseZ
        val dx = floorDiv(relX, size)
        val dz = floorDiv(relZ, size)
        if (dx !in -1..1 || dz !in -1..1) return null
        val cubeMinX = baseX + dx * size
        val cubeMinZ = baseZ + dz * size
        if (x < cubeMinX || x >= cubeMinX + size) return null
        if (z < cubeMinZ || z >= cubeMinZ + size) return null
        return if (getCubes(bunker).any { it.first == dx && it.second == 0 && it.third == dz }) Triple(dx, 0, dz) else null
    }

    /** Derive intended expansion direction from player yaw. */
    fun desiredDirectionFromView(player: Player): Pair<Int,Int> {
        val yaw = player.location.yaw
        val rot = ((yaw % 360) + 360) % 360     // 0..360
        return when {
            rot < 45 || rot >= 315 -> 0 to 1    // south (Z+)
            rot < 135 -> -1 to 0                // west (X-)
            rot < 225 -> 0 to -1                // north (Z-)
            else -> 1 to 0                      // east (X+)
        }
    }

    /** Carve additional cube area when expanded. */
    private fun carveAdditionalCube(bunker: Bunker, dx: Int, dy: Int, dz: Int) {
        val world = ensureBunkerWorld()
        val size = bunker.cubeSize
        val startX = bunker.originChunkX * 16 + dx * size
        val startZ = bunker.originChunkZ * 16 + dz * size
        val startY = bunker.startY
        val endY = startY + size - 1
        if (!world.isChunkLoaded(startX shr 4, startZ shr 4)) {
            world.loadChunk(startX shr 4, startZ shr 4)
        }
        for (x in 0 until size) {
            for (z in 0 until size) {
                val wx = startX + x
                val wz = startZ + z
                world.getBlockAt(wx, startY, wz).type = Material.STONE // floor
                for (y in (startY + 1)..endY) {
                    world.getBlockAt(wx, y, wz).type = Material.AIR
                }
            }
        }
    }

    // Helper floor division that floors toward negative infinity (unlike Kotlin's / which truncs toward 0)
    private fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        val r = a % b
        if (r != 0 && (a xor b) < 0) q -= 1
        return q
    }

    fun resetBunker(owner: UUID): Boolean {
        val bunker = getBunkerByOwner(owner) ?: return false
        // Remove members cache
        val memberUuids = getMembers(bunker).filter { it != owner }
        // Delete DB rows
        db.execUpdate("DELETE FROM bunker_cube WHERE bunker_id=?") { setLong(1, bunker.id) }
        db.execUpdate("DELETE FROM bunker_member WHERE bunker_id=?") { setLong(1, bunker.id) }
        db.execUpdate("DELETE FROM bunker WHERE id=?") { setLong(1, bunker.id) }
        bunkerCache.remove(owner)
        memberUuids.forEach { bunkerCache.remove(it) }
        return true
    }

    fun resetBunkerAndReallocate(ownerPlayer: org.bukkit.entity.Player): Boolean {
        val had = resetBunker(ownerPlayer.uniqueId)
        allocateIfAbsent(ownerPlayer)
        return had
    }

    internal fun countBunkersForTest(): Int = countBunkers()
}
