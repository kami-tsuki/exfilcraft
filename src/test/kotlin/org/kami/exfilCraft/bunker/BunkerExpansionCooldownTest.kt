package org.kami.exfilCraft.bunker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.kami.exfilCraft.testutil.BasePluginTest

class BunkerExpansionCooldownTest: BasePluginTest() {
    @Test
    fun expansionRespectsCooldown() {
        val bunkerService = plugin.services.get(BunkerService::class.java) as BunkerService
        val player = server.addPlayer()
        player.giveExp(10_000)
        val bunker = bunkerService.allocateIfAbsent(player)
        val success1 = bunkerService.extendBunkerFacing(player, bunker, 1 to 0)
        assertThat(success1).isTrue()
        // Immediate second attempt should fail due to cooldown
        val successFail = bunkerService.extendBunkerFacing(player, bunker, 0 to 1)
        assertThat(successFail).isFalse()
        // Manually rewind cooldown and try again
        bunker.lastExpansionTs = (bunker.lastExpansionTs ?: 0) - (plugin.config.getLong("bunker.expansionCooldownMinutes") * 60) - 1
        val success2 = bunkerService.extendBunkerFacing(player, bunker, 0 to 1)
        assertThat(success2).isTrue()
    }
}

