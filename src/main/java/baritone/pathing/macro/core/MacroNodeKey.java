package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

public final class MacroNodeKey {
  private static final long KIND_CELL = 0L;
  private static final long KIND_ANCHOR = 3L;
  private static final int COORD_BITS = 26;
  private static final int COORD_MASK = (1 << COORD_BITS) - 1;
  private static final int COORD_SIGN = 1 << (COORD_BITS - 1);

  private MacroNodeKey() {
  }

  public static long cell(ResourceKey<Level> dimension, MacroStratum stratum, int scale, int cellX, int cellZ) {
    return cell(dimensionId(dimension), stratum, scale, cellX, cellZ);
  }

  public static long cell(int dimensionId, MacroStratum stratum, int scale, int cellX, int cellZ) {
    if (scale < 0 || scale > 7) {
      throw new IllegalArgumentException("scale out of range: " + scale);
    }
    if (dimensionId < 0 || dimensionId > 7) {
      throw new IllegalArgumentException("dimension id out of range: " + dimensionId);
    }
    return (KIND_CELL << 62) | ((long) dimensionId << 59) | ((long) stratum.ordinal() << 55) | ((long) scale << 52) | ((long) packSigned(cellX) << 26) | packSigned(cellZ);
  }

  public static long anchor(long id) {
    if ((id & (3L << 62)) != 0L) {
      throw new IllegalArgumentException("anchor id uses reserved kind bits: " + id);
    }
    return (KIND_ANCHOR << 62) | id;
  }

  public static boolean anchorKey(long key) {
    return (key >>> 62) == KIND_ANCHOR;
  }

  public static int dimensionId(long key) {
    return (int) ((key >>> 59) & 7L);
  }

  public static MacroStratum stratum(long key) {
    return MacroStratum.values()[(int) ((key >>> 55) & 15L)];
  }

  public static int scale(long key) {
    return (int) ((key >>> 52) & 7L);
  }

  public static int cellX(long key) {
    return unpackSigned((int) ((key >>> 26) & COORD_MASK));
  }

  public static int cellZ(long key) {
    return unpackSigned((int) (key & COORD_MASK));
  }

  public static int cellBlocks(long key) {
    return 16 << scale(key);
  }

  public static BetterBlockPos center(long key, int y) {
    int cellBlocks = cellBlocks(key);
    return new BetterBlockPos(cellX(key) * cellBlocks + cellBlocks / 2, y, cellZ(key) * cellBlocks + cellBlocks / 2);
  }

  public static long cellContaining(ResourceKey<Level> dimension, MacroStratum stratum, int scale, BlockPos pos) {
    int cellBlocks = 16 << scale;
    return cell(dimension, stratum, scale, Math.floorDiv(pos.getX(), cellBlocks), Math.floorDiv(pos.getZ(), cellBlocks));
  }

  public static long cellContaining(ResourceKey<Level> dimension, MacroStratum stratum, int scale, BetterBlockPos pos) {
    int cellBlocks = 16 << scale;
    return cell(dimension, stratum, scale, Math.floorDiv(pos.x, cellBlocks), Math.floorDiv(pos.z, cellBlocks));
  }

  private static int dimensionId(ResourceKey<Level> dimension) {
    if (dimension.equals(Level.OVERWORLD)) {
      return 0;
    }
    if (dimension.equals(Level.NETHER)) {
      return 1;
    }
    if (dimension.equals(Level.END)) {
      return 2;
    }
    return 7;
  }

  private static int packSigned(int value) {
    if (value < -COORD_SIGN || value >= COORD_SIGN) {
      throw new IllegalArgumentException("macro cell coordinate out of 26-bit range: " + value);
    }
    return value & COORD_MASK;
  }

  private static int unpackSigned(int value) {
    return (value & COORD_SIGN) == 0 ? value : value | ~COORD_MASK;
  }
}
