package org.kami.exfilCraft.bunker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.kami.exfilCraft.testutil.BasePluginTest

class BunkerExpansionCostTest: BasePluginTest() {
    @Test
    fun expansionCostIncrementsLinearly() {
        val bunkerService = plugin.services.get(BunkerService::class.java) as BunkerService
        val player = server.addPlayer()
        player.giveExp(10_000) // ample XP
        val bunker = bunkerService.allocateIfAbsent(player)
        val baseCost = bunkerService.nextExpansionCostXp(bunker)
        assertThat(baseCost).isGreaterThan(0)
        val success1 = bunkerService.extendBunkerFacing(player, bunker, 1 to 0)
        assertThat(success1).isTrue()
        val cost2 = bunkerService.nextExpansionCostXp(bunker)
        assertThat(cost2).isEqualTo(baseCost * 2)
        // Fast-forward cooldown for next expansion
        bunker.lastExpansionTs = (bunker.lastExpansionTs ?: 0) - 6000
        val success2 = bunkerService.extendBunkerFacing(player, bunker, 0 to 1)
        assertThat(success2).isTrue()
        val cost3 = bunkerService.nextExpansionCostXp(bunker)
        assertThat(cost3).isEqualTo(baseCost * 3)
    }
}
