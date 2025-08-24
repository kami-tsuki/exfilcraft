package org.kami.exfilCraft.command

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.kami.exfilCraft.ExfilCraft

class CommandFrameworkTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: ExfilCraft
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ExfilCraft::class.java)
        player = server.addPlayer()
        player.isOp = true // allow admin command coverage
        player.drainMessages() // clear any join messages
    }

    @AfterEach
    fun tearDown() { MockBukkit.unmock() }

    // Helper to drain all queued chat messages (String form) from PlayerMock
    private fun PlayerMock.drainMessages(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val next = this.nextMessage() ?: break
            out += next
        }
        return out
    }

    @Test
    fun `exfil version command outputs version`() {
        val ok = server.dispatchCommand(player, "exfil version")
        assertTrue(ok, "Command should succeed")
        val msgs = player.drainMessages()
        assertTrue(msgs.any { it.contains("ExfilCraft") }, "Should mention ExfilCraft")
        assertTrue(msgs.any { it.contains(plugin.description.version) }, "Should include version")
    }

    @Test
    fun `bunker help lists info subcommand`() {
        server.dispatchCommand(player, "bunker help")
        val msgs = player.drainMessages()
        assertTrue(msgs.any { it.lowercase().contains("bunker info") }, "Help should list bunker info subcommand")
    }

    @Test
    fun `raid nested help for queue status`() {
        server.dispatchCommand(player, "raid help queue:status")
        val msgs = player.drainMessages()
        assertTrue(msgs.any { it.lowercase().contains("help: queue:status") }, "Should show specific help header for queue:status")
    }

    @Test
    fun `unknown subcommand suggests closest`() {
        // Drain any previous
        player.drainMessages()
        server.dispatchCommand(player, "raid statu") // missing 's'
        val msgs = player.drainMessages()
        assertTrue(msgs.any { it.lowercase().contains("did you mean") }, "Should suggest close match")
    }
}
