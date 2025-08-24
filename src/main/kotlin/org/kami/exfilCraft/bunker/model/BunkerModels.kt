package org.kami.exfilCraft.bunker.model

import java.util.UUID

data class Bunker(
    val id: Long,
    val ownerUuid: UUID,
    val originChunkX: Int,
    val originChunkZ: Int,
    val startY: Int,
    val cubeSize: Int,
    var cubesCount: Int,
    var lastExpansionTs: Long?,
    val createdAt: Long,
    var updatedAt: Long
)

data class BunkerMember(val bunkerId: Long, val memberUuid: UUID, val role: String)

data class BunkerInvite(
    val id: Long,
    val bunkerId: Long,
    val inviteeUuid: UUID,
    val inviterUuid: UUID,
    val createdAt: Long,
    val expiresAt: Long,
    val accepted: Boolean
)

