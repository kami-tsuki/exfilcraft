/* MIT License 2025 tsuki */
package org.kami.exfilCraft.db

import org.kami.exfilCraft.config.ConfigService
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DatabaseService(private val plugin: JavaPlugin, private val config: ConfigService) {
    private var connection: Connection? = null
    private val lock = ReentrantLock()

    fun init() {
        val filePath = config.databaseFile
        val file = File(filePath)
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection!!.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL;")
        }
        ensureMigrationsTable()
        // Phase 1 initial schema migrations
        applyMigration(1) {
            execUpdate("CREATE TABLE player_profile (uuid TEXT PRIMARY KEY, total_xp INTEGER NOT NULL DEFAULT 0, currency INTEGER NOT NULL DEFAULT 0, unlocks TEXT NOT NULL DEFAULT '[]', last_raid_ts INTEGER, last_bunker_x INTEGER, last_bunker_y INTEGER, last_bunker_z INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);")
            execUpdate("CREATE TABLE bunker (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT UNIQUE NOT NULL, origin_chunk_x INTEGER NOT NULL, origin_chunk_z INTEGER NOT NULL, start_y INTEGER NOT NULL, cube_size INTEGER NOT NULL, cubes_count INTEGER NOT NULL, last_expansion_ts INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);")
            execUpdate("CREATE TABLE bunker_member (bunker_id INTEGER NOT NULL, member_uuid TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY(bunker_id, member_uuid));")
            execUpdate("CREATE TABLE bunker_invite (id INTEGER PRIMARY KEY AUTOINCREMENT, bunker_id INTEGER NOT NULL, invitee_uuid TEXT NOT NULL, inviter_uuid TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, accepted INTEGER NOT NULL DEFAULT 0);")
            execUpdate("CREATE INDEX idx_bunker_owner ON bunker(owner_uuid);")
            execUpdate("CREATE INDEX idx_invite_inv ON bunker_invite(invitee_uuid);")
        }
    }

    private fun ensureMigrationsTable() {
        execUpdate("CREATE TABLE IF NOT EXISTS migrations (id INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL);")
    }

    private fun hasMigration(id: Int): Boolean = query("SELECT 1 FROM migrations WHERE id=? LIMIT 1") {
        setInt(1, id)
        executeQuery().use { rs -> rs.next() }
    } ?: false

    private fun recordMigration(id: Int) {
        execUpdate("INSERT INTO migrations(id, applied_at) VALUES(?, ?)") {
            setInt(1, id)
            setLong(2, Instant.now().epochSecond)
        }
    }

    private fun applyMigration(id: Int, block: () -> Unit) {
        if (hasMigration(id)) return
        block()
        recordMigration(id)
    }

    private fun <T> query(sql: String, binder: (java.sql.PreparedStatement.() -> T)): T? = lock.withLock {
        val conn = connection ?: return null
        conn.prepareStatement(sql).use { ps -> return ps.binder() }
    }

    fun execUpdate(sql: String, binder: (java.sql.PreparedStatement.() -> Unit)? = null) = lock.withLock {
        val conn = connection ?: throw IllegalStateException("DB not initialized")
        conn.prepareStatement(sql).use { ps ->
            if (binder != null) ps.binder()
            ps.executeUpdate()
        }
    }

    fun <T> selectOne(sql: String, binder: java.sql.PreparedStatement.() -> Unit = {}, mapper: (java.sql.ResultSet) -> T): T? = lock.withLock {
        val conn = connection ?: throw IllegalStateException("DB not initialized")
        conn.prepareStatement(sql).use { ps ->
            ps.binder()
            ps.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
        }
    }

    fun <T> selectAll(sql: String, binder: java.sql.PreparedStatement.() -> Unit = {}, mapper: (java.sql.ResultSet) -> T): List<T> = lock.withLock {
        val conn = connection ?: throw IllegalStateException("DB not initialized")
        conn.prepareStatement(sql).use { ps ->
            ps.binder()
            ps.executeQuery().use { rs ->
                val list = mutableListOf<T>()
                while (rs.next()) list += mapper(rs)
                list
            }
        }
    }

    fun close() { try { connection?.close() } catch (_: SQLException) {} }
}

