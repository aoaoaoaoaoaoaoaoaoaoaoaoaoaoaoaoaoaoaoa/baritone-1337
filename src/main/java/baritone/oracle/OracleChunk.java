package baritone.oracle;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

final class OracleChunk {
  private static final BlockState AIR = Blocks.AIR.defaultBlockState();

  private final int chunkX;
  private final int chunkZ;
  private final int minY;
  private final int height;
  private final BlockState[] states;
  private final int[][] heightmaps;

  private OracleChunk(int chunkX, int chunkZ, int minY, int height, BlockState[] states, int[][] heightmaps) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minY = minY;
    this.height = height;
    this.states = states;
    this.heightmaps = heightmaps;
  }

  static OracleChunk snapshot(LevelChunk chunk, int minY, int height) {
    BlockState[] states = new BlockState[16 * height * 16];
    LevelChunkSection[] sections = chunk.getSections();
    for (int y = 0; y < height; y++) {
      int sectionIndex = y >> 4;
      LevelChunkSection section = sections[sectionIndex];
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          states[index(x, y, z, height)] = section.hasOnlyAir() ? AIR : section.getBlockState(x, y & 15, z);
        }
      }
    }
    int[][] heightmaps = new int[Heightmap.Types.values().length][16 * 16];
    for (Heightmap.Types type : Heightmap.Types.values()) {
      int[] heights = heightmaps[type.ordinal()];
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          heights[z * 16 + x] = chunk.getHeight(type, x, z);
        }
      }
    }
    return new OracleChunk(chunk.getPos().x(), chunk.getPos().z(), minY, height, states, heightmaps);
  }

  BlockState state(int localX, int y, int localZ) {
    int relY = y - minY;
    if (relY < 0 || relY >= height) {
      return AIR;
    }
    return states[index(localX & 15, relY, localZ & 15, height)];
  }

  FluidState fluid(int localX, int y, int localZ) {
    return state(localX, y, localZ).getFluidState();
  }

  int height(Heightmap.Types type, int localX, int localZ) {
    return heightmaps[type.ordinal()][(localZ & 15) * 16 + (localX & 15)];
  }

  int chunkX() {
    return chunkX;
  }

  int chunkZ() {
    return chunkZ;
  }

  private static int index(int x, int relY, int z, int height) {
    return (relY * 16 + z) * 16 + x;
  }
}
