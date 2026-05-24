package baritone.pathing.farfield;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record FarfieldColumnKey(ResourceKey<Level> dimension, int cellX, int cellZ) {
  public static final int CELL_BLOCKS = 16;

  public static FarfieldColumnKey containing(ResourceKey<Level> dimension, int blockX, int blockZ) {
    return new FarfieldColumnKey(dimension, Math.floorDiv(blockX, CELL_BLOCKS), Math.floorDiv(blockZ, CELL_BLOCKS));
  }

  public int centerX() {
    return cellX * CELL_BLOCKS + (CELL_BLOCKS >>> 1);
  }

  public int centerZ() {
    return cellZ * CELL_BLOCKS + (CELL_BLOCKS >>> 1);
  }
}
