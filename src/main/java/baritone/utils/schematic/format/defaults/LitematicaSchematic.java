package baritone.utils.schematic.format.defaults;

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import baritone.utils.schematic.format.BlockStateCodec;
import baritone.utils.schematic.format.SchematicVolume;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.util.Collections;

public final class LitematicaSchematic extends CompositeSchematic implements IStaticSchematic {

  public LitematicaSchematic(CompoundTag nbt) {
    super(0, 0, 0);
    fillInSchematic(nbt);
  }

  private static CompoundTag[] getRegions(CompoundTag nbt) {
    return nbt.getCompound("Regions").map(CompoundTag::values).map(r -> r.stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast).toArray(CompoundTag[]::new))
      .orElse(new CompoundTag[0]);
  }

  private static int getMinOfSubregion(CompoundTag subReg, String s) {
    int a = subReg.getCompound("Position").flatMap(position -> position.getInt(s)).orElse(0);
    int b = subReg.getCompound("Size").flatMap(size -> size.getInt(s)).orElse(0);
    return Math.min(a, a + b + 1);
  }

  private static BlockState[] getBlockList(ListTag blockStatePalette) {
    BlockState[] blockList = new BlockState[blockStatePalette.size()];
    for (int i = 0; i < blockStatePalette.size(); i++) {
      blockList[i] = BlockStateCodec.parseLitematica((CompoundTag) blockStatePalette.get(i));
    }
    return blockList;
  }

  private static int getBitsPerBlock(int amountOfBlockTypes) {
    if (amountOfBlockTypes <= 0) {
      throw new IllegalArgumentException("Litematica palette is empty");
    }
    return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(amountOfBlockTypes - 1));
  }

  private static SchematicVolume volume(CompoundTag subReg) {
    return SchematicVolume.absoluteDimensions(subReg.getCompound("Size").orElseThrow(() -> new IllegalArgumentException("Litematica subregion missing Size")), "x", "y", "z");
  }

  private static int getMinOfSchematic(CompoundTag nbt, String s) {
    int n = Integer.MAX_VALUE;
    for (CompoundTag subReg : getRegions(nbt)) {
      n = Math.min(n, getMinOfSubregion(subReg, s));
    }
    return n;
  }

  private void fillInSchematic(CompoundTag nbt) {
    Vec3i offsetMinCorner = new Vec3i(getMinOfSchematic(nbt, "x"), getMinOfSchematic(nbt, "y"), getMinOfSchematic(nbt, "z"));
    for (CompoundTag subReg : getRegions(nbt)) {
      ListTag usedBlockTypes = subReg.getListOrEmpty("BlockStatePalette");
      BlockState[] blockList = getBlockList(usedBlockTypes);
      SchematicVolume regionVolume = volume(subReg);

      int bitsPerBlock = getBitsPerBlock(usedBlockTypes.size());
      long[] blockStateArray = subReg.getLongArray("BlockStates").orElse(new long[0]);

      LitematicaBitArray bitArray = new LitematicaBitArray(bitsPerBlock, regionVolume.intVolume(), blockStateArray);
      writeSubregionIntoSchematic(subReg, offsetMinCorner, regionVolume, blockList, bitArray);
    }
  }

  private void writeSubregionIntoSchematic(CompoundTag subReg, Vec3i offsetMinCorner, SchematicVolume volume, BlockState[] blockList, LitematicaBitArray bitArray) {
    int offsetX = getMinOfSubregion(subReg, "x") - offsetMinCorner.getX();
    int offsetY = getMinOfSubregion(subReg, "y") - offsetMinCorner.getY();
    int offsetZ = getMinOfSubregion(subReg, "z") - offsetMinCorner.getZ();
    BlockState[][][] states = volume.blockStates();
    int index = 0;
    for (int y = 0; y < volume.y(); y++) {
      for (int z = 0; z < volume.z(); z++) {
        for (int x = 0; x < volume.x(); x++) {
          int paletteIndex = bitArray.getAt(index++);
          if (paletteIndex >= blockList.length) {
            throw new IllegalArgumentException("Litematica palette index " + paletteIndex + " exceeds palette size " + blockList.length);
          }
          states[x][z][y] = blockList[paletteIndex];
        }
      }
    }
    this.put(new StaticSchematic(states), offsetX, offsetY, offsetZ);
  }

  @Override
  public BlockState getDirect(int x, int y, int z) {
    return desiredState(x, y, z, null, Collections.emptyList());
  }

  private static class LitematicaBitArray {

    private final long[] longArray;
    private final int bitsPerEntry;
    private final long maxEntryValue;
    private final long arraySize;

    public LitematicaBitArray(int bitsPerEntryIn, long arraySizeIn, @Nullable long[] longArrayIn) {
      Validate.inclusiveBetween(1L, 32L, bitsPerEntryIn);
      Validate.isTrue(arraySizeIn >= 0L, "Negative Litematica bit-array size");
      this.arraySize = arraySizeIn;
      this.bitsPerEntry = bitsPerEntryIn;
      this.maxEntryValue = (1L << bitsPerEntryIn) - 1L;
      long requiredWords = requiredWords(arraySizeIn, bitsPerEntryIn);
      this.longArray = longArrayIn == null ? new long[Math.toIntExact(requiredWords)] : longArrayIn;
      if (this.longArray.length < requiredWords) {
        throw new IllegalArgumentException("Litematica BlockStates has " + this.longArray.length + " words, expected at least " + requiredWords);
      }
    }

    public int getAt(long index) {
      Validate.inclusiveBetween(0L, this.arraySize - 1L, index);
      long startOffset = index * (long) this.bitsPerEntry;
      int startArrIndex = (int) (startOffset >> 6);
      int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
      int startBitOffset = (int) (startOffset & 0x3F);

      if (startArrIndex == endArrIndex) {
        return (int) (this.longArray[startArrIndex] >>> startBitOffset & this.maxEntryValue);
      }
      int endOffset = 64 - startBitOffset;
      return (int) ((this.longArray[startArrIndex] >>> startBitOffset | this.longArray[endArrIndex] << endOffset) & this.maxEntryValue);
    }

    private static long requiredWords(long entries, int bitsPerEntry) {
      return Math.addExact(Math.multiplyExact(entries, bitsPerEntry), 63L) >>> 6;
    }
  }
}
