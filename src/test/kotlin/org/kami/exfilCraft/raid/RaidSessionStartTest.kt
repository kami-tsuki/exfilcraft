package org.kami.exfilCraft.raid

import org.assertj.core.api.Assertions.assertThat
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.junit.jupiter.api.Test
import org.kami.exfilCraft.testutil.BasePluginTest
import org.kami.exfilCraft.raid.RaidTemplate
import org.kami.exfilCraft.raid.RaidService
import org.kami.exfilCraft.raid.RaidWorldFactory

class RaidSessionStartTest: BasePluginTest() {
    @Test
    fun forceStartCreatesSession() {
        val raidService = plugin.services.get(RaidService::class.java) as RaidService
        // Override world factory with minimal deterministic factory (valid name characters only)
        raidService.setWorldFactoryForTest(object: RaidWorldFactory {
            override fun create(template: RaidTemplate, id: Long) = org.bukkit.Bukkit.createWorld(
                WorldCreator("raidtest_${id}_${template.id}_${System.currentTimeMillis()}").type(WorldType.NORMAL)
            )
        })
        val player = server.addPlayer()
        val started = raidService.adminForceStart(null, player)
        assertThat(started).isTrue()
        // process tasks
        server.scheduler.performTicks(5L)
        assertThat(raidService.activeSessionCountForTest()).isEqualTo(1)
        val session = raidService.testSessions().first()
        assertThat(session.world).isNotNull()
        assertThat(session.players).contains(player.uniqueId)
        assertThat(session.extractionPoint).isNotNull()
    }
}
