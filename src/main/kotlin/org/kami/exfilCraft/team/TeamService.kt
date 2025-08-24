/* MIT License 2025 tsuki */
package org.kami.exfilCraft.team

import java.util.*
import java.time.Instant

data class TeamInvite(val from: UUID, val to: UUID, val createdAt: Long = Instant.now().epochSecond, val expiresAt: Long = createdAt + 900)

data class Team(val leader: UUID, val members: MutableSet<UUID> = mutableSetOf()) {
    init { members += leader }
}

class TeamService {
    private val teams: MutableMap<UUID, Team> = mutableMapOf() // leader -> team
    private val memberIndex: MutableMap<UUID, UUID> = mutableMapOf() // member -> leader
    private val invites: MutableList<TeamInvite> = mutableListOf()

    fun getTeam(uuid: UUID): Team? = memberIndex[uuid]?.let { teams[it] }

    fun create(uuid: UUID): Boolean {
        if (getTeam(uuid) != null) return false
        val t = Team(uuid)
        teams[uuid] = t
        memberIndex[uuid] = uuid
        return true
    }

    fun disband(uuid: UUID): Boolean {
        val team = getTeam(uuid) ?: return false
        if (team.leader != uuid) return false
        team.members.forEach { memberIndex.remove(it) }
        teams.remove(team.leader)
        invites.removeIf { it.from == uuid || it.to == uuid }
        return true
    }

    fun leave(uuid: UUID): Boolean {
        val team = getTeam(uuid) ?: return false
        if (team.leader == uuid) return false // leader must disband
        team.members.remove(uuid)
        memberIndex.remove(uuid)
        return true
    }

    fun invite(from: UUID, to: UUID): Boolean {
        val team = getTeam(from) ?: return false
        if (getTeam(to) != null) return false
        invites.removeIf { it.to == to }
        invites += TeamInvite(from, to)
        return true
    }

    fun accept(to: UUID, from: UUID): Boolean {
        val inv = invites.firstOrNull { it.to == to && it.from == from && it.expiresAt >= Instant.now().epochSecond } ?: return false
        val team = teams[from] ?: return false
        if (getTeam(to) != null) return false
        team.members += to
        memberIndex[to] = team.leader
        invites.removeIf { it.to == to }
        return true
    }

    fun members(uuid: UUID): Set<UUID> = getTeam(uuid)?.members ?: emptySet()
}

