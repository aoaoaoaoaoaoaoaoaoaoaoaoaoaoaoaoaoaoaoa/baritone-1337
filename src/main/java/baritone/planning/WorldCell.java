package baritone.planning;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.BlockKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record WorldCell(ResourceKey<Level> dimension, int x, int y, int z) {
  public static WorldCell of(ResourceKey<Level> dimension, BlockPos pos) {
    return new WorldCell(dimension, pos.getX(), pos.getY(), pos.getZ());
  }

  public long xyzKey() {
    return BlockKey.pack(x, y, z);
  }

  public BetterBlockPos pos() {
    return new BetterBlockPos(x, y, z);
  }
}
