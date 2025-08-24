import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.block.BlockMock
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Example test class demonstrating proper MockBukkit 4.72.8 usage
 * Based on official documentation from https://github.com/MockBukkit/MockBukkit
 */
class MockBukkitExampleTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin

    @BeforeEach
    fun setUp() {
        // Initialize MockBukkit server
        server = MockBukkit.mock()

        // Create a mock plugin for testing
        plugin = MockBukkit.createMockPlugin()
    }

    @AfterEach
    fun tearDown() {
        // Always unmock to clean up resources
        MockBukkit.unmock()
    }

    @Test
    fun `test server is running`() {
        assertTrue(server.isStopping.not(), "Server should be running")
        assertNotNull(server.pluginManager, "Plugin manager should be available")
    }

    @Test
    fun `test player creation and basic operations`() {
        // Create a mock player
        val player = server.addPlayer()

        assertNotNull(player, "Player should be created")
        assertTrue(player.isOnline, "Player should be online")
        assertEquals(1, server.onlinePlayers.size, "Should have exactly one online player")

        // Test player properties
        player.name = "TestPlayer"
        assertEquals("TestPlayer", player.name, "Player name should be set correctly")

        // Test player location
        val world = server.addSimpleWorld("test_world")
        player.teleport(world.spawnLocation)
        assertEquals(world, player.world, "Player should be in the test world")
    }

    @Test
    fun `test multiple players`() {
        // Set multiple players at once
        server.setPlayers(3)
        assertEquals(3, server.onlinePlayers.size, "Should have 3 online players")

        // Access individual players
        val firstPlayer = server.getPlayer(0)
        val secondPlayer = server.getPlayer(1)
        val thirdPlayer = server.getPlayer(2)

        assertNotNull(firstPlayer, "First player should exist")
        assertNotNull(secondPlayer, "Second player should exist")
        assertNotNull(thirdPlayer, "Third player should exist")

        // All players should be different
        assertNotEquals(firstPlayer.uniqueId, secondPlayer.uniqueId)
        assertNotEquals(secondPlayer.uniqueId, thirdPlayer.uniqueId)
    }

    @Test
    fun `test world creation and block manipulation`() {
        // Create a simple world with dirt and air
        val world = server.addSimpleWorld("test_world")

        assertNotNull(world, "World should be created")
        assertEquals("test_world", world.name, "World name should match")

        // Test block access and modification
        val block = world.getBlockAt(0, 64, 0)
        assertNotNull(block, "Block should exist")

        // Change block type
        block.type = Material.DIAMOND_BLOCK
        assertEquals(Material.DIAMOND_BLOCK, block.type, "Block type should be updated")
    }

    @Test
    fun `test player block breaking simulation`() {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("test_world")

        // Place a block to break
        val block = world.getBlockAt(10, 64, 10)
        block.type = Material.STONE

        // Simulate block breaking
        player.simulateBlockBreak(block)

        // Check that the block was actually broken by verifying it became air
        assertEquals(Material.AIR, block.type, "Block should become air after breaking")
    }

    @Test
    fun `test event firing during block break`() {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("test_world")

        val block = world.getBlockAt(5, 64, 5)
        block.type = Material.COBBLESTONE

        var eventFired = false
        var eventPlayer: PlayerMock? = null
        var eventBlock: BlockMock? = null

        // Register event listener
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onBlockBreak(event: BlockBreakEvent) {
                eventFired = true
                eventPlayer = event.player as PlayerMock
                eventBlock = event.block as BlockMock
            }
        }, plugin)

        // Simulate block breaking
        player.simulateBlockBreak(block)

        assertTrue(eventFired, "BlockBreakEvent should be fired")
        assertEquals(player, eventPlayer, "Event should contain the correct player")
        assertEquals(block, eventBlock, "Event should contain the correct block")
    }

    @Test
    fun `test command execution`() {
        // Register a simple dummy command "foo" into the internal command map via reflection
        val cmdMapField = server.javaClass.getDeclaredField("commandMap").apply { isAccessible = true }
        val commandMap = cmdMapField.get(server) as org.bukkit.command.CommandMap
        val dummy = object : org.bukkit.command.Command("foo") {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>): Boolean {
                sender.sendMessage("dummy ok")
                return true
            }
        }
        commandMap.register("exfilcraft-test", dummy)
        val player = server.addPlayer()
        val result = server.execute("foo", player)
        assertTrue(result.hasSucceeded(), "Foo command should succeed")
    }

    @Test
    fun `test scheduler functionality`() {
        val scheduler = server.scheduler
        var taskExecuted = false

        // Schedule a simple task
        scheduler.runTask(plugin, Runnable {
            taskExecuted = true
        })

        // Execute pending tasks
        scheduler.performOneTick()

        assertTrue(taskExecuted, "Scheduled task should be executed")
    }

    @Test
    fun `test inventory operations`() {
        val player = server.addPlayer()
        val inventory = player.inventory

        // Test adding items
        inventory.addItem(org.bukkit.inventory.ItemStack(Material.DIAMOND, 5))

        val diamondStack = inventory.contents.filterNotNull()
            .firstOrNull { it.type == Material.DIAMOND }

        assertNotNull(diamondStack, "Diamond stack should be in inventory")
        assertEquals(5, diamondStack?.amount, "Should have 5 diamonds")
    }

    @Test
    fun `test plugin mock functionality`() {
        assertNotNull(plugin, "Mock plugin should be created")
        assertTrue(plugin.isEnabled, "Mock plugin should be enabled")
        assertNotNull(plugin.description, "Plugin should have description")
    }
}
