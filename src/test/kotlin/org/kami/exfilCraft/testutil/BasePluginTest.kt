package org.kami.exfilCraft.testutil

import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.kami.exfilCraft.ExfilCraft
import java.io.File

/** Base test bootstrapping a MockBukkit server and the plugin. */
abstract class BasePluginTest {
    protected lateinit var server: ServerMock
    protected lateinit var plugin: ExfilCraft

    @BeforeEach
    fun setupServer() {
        // Ensure clean DB between runs
        val dbFile = File("plugins/ExfilCraft/data.db")
        if (dbFile.exists()) dbFile.delete()
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ExfilCraft::class.java)
    }

    @AfterEach
    fun tearDown() {
        try { plugin.onDisable() } catch (_: Throwable) {}
        MockBukkit.unmock()
        val dbFile = File("plugins/ExfilCraft/data.db")
        if (dbFile.exists()) dbFile.delete()
    }
}
