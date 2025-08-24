package org.kami.exfilCraft.bunker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.kami.exfilCraft.testutil.BasePluginTest
import java.util.*

class BunkerAllocationTest: BasePluginTest() {

    @Test
    fun allocationCreatesInitialCube() {
        val bunkerService = plugin.services.get(BunkerService::class.java) as BunkerService
        val before = bunkerService.countBunkersForTest()
        val player = server.addPlayer() // triggers join listener -> allocation
        val afterJoin = bunkerService.countBunkersForTest()
        assertThat(afterJoin).isEqualTo(before + 1)
        val bunker = bunkerService.allocateIfAbsent(player) // should not create new
        val afterAllocate = bunkerService.countBunkersForTest()
        assertThat(afterAllocate).isEqualTo(afterJoin)
        assertThat(bunker.cubesCount).isEqualTo(1)
        val cubes = bunkerService.getCubes(bunker)
        assertThat(cubes).contains(Triple(0,0,0))
        // Teleport should not throw
        bunkerService.teleportToBunker(player, bunker)
        assertThat(player.world.name).isEqualTo(plugin.config.getString("bunker.worldName"))
    }
}
