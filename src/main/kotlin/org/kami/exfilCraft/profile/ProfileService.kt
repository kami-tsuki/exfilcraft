/* MIT License 2025 tsuki */
package org.kami.exfilCraft.profile

import org.kami.exfilCraft.db.DatabaseService
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID

data class PlayerProfile(
    val uuid: UUID,
    var totalXp: Long,
    var currency: Long,
    var unlocksJson: String,
    var lastRaidTs: Long?,
    var lastBunkerX: Int?,
    var lastBunkerY: Int?,
    var lastBunkerZ: Int?,
    var createdAt: Long,
    var updatedAt: Long,
    var starterGiven: Boolean
)

class ProfileService(private val plugin: JavaPlugin, private val db: DatabaseService) {

    fun getOrCreate(uuid: UUID): PlayerProfile {
        val existing = get(uuid)
        if (existing != null) return existing
        val now = Instant.now().epochSecond
        db.execUpdate("INSERT INTO player_profile(uuid,total_xp,currency,unlocks,last_raid_ts,last_bunker_x,last_bunker_y,last_bunker_z,created_at,updated_at,starter_given) VALUES(?,?,?,?,?,?,?,?,?,?,?)") {
            setString(1, uuid.toString())
            setLong(2, 0)
            setLong(3, 0)
            setString(4, "[]")
            setObject(5, null)
            setObject(6, null)
            setObject(7, null)
            setObject(8, null)
            setLong(9, now)
            setLong(10, now)
            setInt(11, 0)
        }
        return get(uuid)!!
    }

    fun get(uuid: UUID): PlayerProfile? = db.selectOne("SELECT * FROM player_profile WHERE uuid=?", { setString(1, uuid.toString()) }) { rs ->
        PlayerProfile(
            UUID.fromString(rs.getString("uuid")),
            rs.getLong("total_xp"),
            rs.getLong("currency"),
            rs.getString("unlocks"),
            rs.getLong("last_raid_ts").takeIf { !rs.wasNull() },
            rs.getInt("last_bunker_x").takeIf { !rs.wasNull() },
            rs.getInt("last_bunker_y").takeIf { !rs.wasNull() },
            rs.getInt("last_bunker_z").takeIf { !rs.wasNull() },
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getInt("starter_given") == 1
        )
    }

    fun markStarterGiven(uuid: UUID) {
        db.execUpdate("UPDATE player_profile SET starter_given=1, updated_at=? WHERE uuid=?") {
            setLong(1, Instant.now().epochSecond)
            setString(2, uuid.toString())
        }
    }
}
