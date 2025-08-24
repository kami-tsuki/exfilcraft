/* MIT License 2025 tsuki */
package org.kami.exfilCraft.world

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.*

class BunkerWorldGenerator : ChunkGenerator() {
    @Suppress("DEPRECATION")
    override fun generateChunkData(world: World, random: Random, chunkX: Int, chunkZ: Int, biome: BiomeGrid): ChunkData {
        val chunk = createChunkData(world)
        val minY = world.minHeight            // expected -64
        val maxY = world.maxHeight - 1        // top usable index (319 in 1.18+ default range)
        val deepslateTop = -1                 // inclusive top of deepslate layer
        val stoneTop = maxY - 2               // leave last layer for bedrock ceiling

        // Floor bedrock
        for (x in 0 until 16) for (z in 0 until 16) chunk.setBlock(x, minY, z, Material.BEDROCK)
        // Deepslate layer (-63 .. -1)
        for (y in (minY + 1)..deepslateTop) {
            for (x in 0 until 16) for (z in 0 until 16) chunk.setBlock(x, y, z, Material.DEEPSLATE)
        }
        // Stone layer (0 .. stoneTop)
        for (y in 0..stoneTop) {
            if (y <= deepslateTop) continue // safety
            for (x in 0 until 16) for (z in 0 until 16) chunk.setBlock(x, y, z, Material.STONE)
        }
        // Ceiling bedrock (maxY)
        for (x in 0 until 16) for (z in 0 until 16) chunk.setBlock(x, maxY, z, Material.BEDROCK)
        return chunk
    }
}
