package baritone.utils.schematic.format;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record SchematicVolume(int x, int y, int z) {

  public SchematicVolume {
    if (x < 0 || y < 0 || z < 0) {
      throw new IllegalArgumentException("Negative schematic dimensions: " + x + "×" + y + "×" + z);
    }
    if (longVolume(x, y, z) > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Schematic volume exceeds Java array limits: " + x + "×" + y + "×" + z);
    }
  }

  public static SchematicVolume dimensions(CompoundTag tag, String xKey, String yKey, String zKey) {
    return new SchematicVolume(nonNegative(read(tag, xKey), xKey), nonNegative(read(tag, yKey), yKey), nonNegative(read(tag, zKey), zKey));
  }

  public static SchematicVolume absoluteDimensions(CompoundTag tag, String xKey, String yKey, String zKey) {
    return new SchematicVolume(abs(read(tag, xKey), xKey), abs(read(tag, yKey), yKey), abs(read(tag, zKey), zKey));
  }

  public int intVolume() {
    return Math.toIntExact(longVolume());
  }

  public long longVolume() {
    return longVolume(x, y, z);
  }

  public BlockState[][][] blockStates() {
    return new BlockState[x][z][y];
  }

  private static int read(CompoundTag tag, String key) {
    return tag.getInt(key).orElseThrow(() -> new IllegalArgumentException("Missing schematic dimension: " + key));
  }

  private static int nonNegative(int value, String key) {
    if (value < 0) {
      throw new IllegalArgumentException("Negative schematic dimension " + key + ": " + value);
    }
    return value;
  }

  private static int abs(int value, String key) {
    if (value == Integer.MIN_VALUE) {
      throw new IllegalArgumentException("Unrepresentable schematic dimension " + key + ": " + value);
    }
    return Math.abs(value);
  }

  private static long longVolume(int x, int y, int z) {
    return (long) x * y * z;
  }
}
