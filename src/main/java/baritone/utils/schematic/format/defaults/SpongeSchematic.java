package baritone.utils.schematic.format.defaults;

import baritone.utils.schematic.StaticSchematic;
import baritone.utils.schematic.format.BlockStateCodec;
import baritone.utils.schematic.format.SchematicVolume;
import baritone.utils.type.VarInt;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * @author Brady
 * @since 12/27/2019
 */
public final class SpongeSchematic extends StaticSchematic {

  public SpongeSchematic(CompoundTag nbt) {
    SchematicVolume volume = SchematicVolume.dimensions(nbt, "Width", "Height", "Length");
    this.x = volume.x();
    this.y = volume.y();
    this.z = volume.z();
    this.states = volume.blockStates();

    Int2ObjectArrayMap<BlockState> palette = new Int2ObjectArrayMap<>();
    CompoundTag paletteTag = nbt.getCompound("Palette").orElseThrow(() -> new IllegalArgumentException("Missing Sponge palette"));
    for (String tag : paletteTag.keySet()) {
      int index = paletteTag.getInt(tag).orElseThrow(() -> new IllegalArgumentException("Palette entry has non-integer index: " + tag));
      palette.put(index, BlockStateCodec.parseSponge(tag));
    }

    int[] blockData = readBlockData(nbt.getByteArray("BlockData").orElseThrow(() -> new IllegalArgumentException("Missing Sponge BlockData")), volume.intVolume());
    for (int y = 0; y < this.y; y++) {
      for (int z = 0; z < this.z; z++) {
        for (int x = 0; x < this.x; x++) {
          int index = (y * this.z + z) * this.x + x;
          int paletteIndex = blockData[index];
          BlockState state = palette.get(paletteIndex);
          if (state == null) {
            throw new IllegalArgumentException("Invalid Sponge palette index " + paletteIndex + " at block " + index);
          }
          this.states[x][z][y] = state;
        }
      }
    }
  }

  private static int[] readBlockData(byte[] rawBlockData, int blockCount) {
    int[] blockData = new int[blockCount];
    int offset = 0;
    for (int i = 0; i < blockData.length; i++) {
      if (offset >= rawBlockData.length) {
        throw new IllegalArgumentException("Sponge BlockData ended after " + i + " of " + blockCount + " block ids");
      }
      VarInt varInt = VarInt.read(rawBlockData, offset);
      blockData[i] = varInt.getValue();
      offset += varInt.getSize();
    }
    return blockData;
  }
}
