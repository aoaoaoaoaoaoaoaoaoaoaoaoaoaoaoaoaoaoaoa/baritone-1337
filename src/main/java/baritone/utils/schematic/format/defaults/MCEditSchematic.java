package baritone.utils.schematic.format.defaults;

import baritone.utils.schematic.StaticSchematic;
import baritone.utils.schematic.format.BlockStateCodec;
import baritone.utils.schematic.format.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.fixes.ItemIdFix;
import net.minecraft.world.level.block.state.BlockState;

/**
 * @author Brady
 * @since 12/27/2019
 */
public final class MCEditSchematic extends StaticSchematic {

  public MCEditSchematic(CompoundTag schematic) {
    String type = schematic.getString("Materials").orElseThrow();
    if (!type.equals("Alpha")) {
      throw new IllegalStateException("bad schematic " + type);
    }
    SchematicVolume volume = SchematicVolume.dimensions(schematic, "Width", "Height", "Length");
    this.x = volume.x();
    this.y = volume.y();
    this.z = volume.z();
    int blockCount = volume.intVolume();
    byte[] blocks = schematic.getByteArray("Blocks").orElseThrow();
    if (blocks.length < blockCount) {
      throw new IllegalArgumentException("MCEdit Blocks has " + blocks.length + " entries, expected " + blockCount);
    }

    byte[] additional = null;
    if (schematic.contains("AddBlocks")) {
      byte[] addBlocks = schematic.getByteArray("AddBlocks").orElseThrow();
      additional = new byte[addBlocks.length * 2];
      for (int i = 0; i < addBlocks.length; i++) {
        additional[i * 2] = (byte) ((addBlocks[i] >> 4) & 0xF);
        additional[i * 2 + 1] = (byte) (addBlocks[i] & 0xF);
      }
      if (additional.length < blockCount) {
        throw new IllegalArgumentException("MCEdit AddBlocks has " + additional.length + " unpacked entries, expected " + blockCount);
      }
    }
    this.states = volume.blockStates();
    for (int y = 0; y < this.y; y++) {
      for (int z = 0; z < this.z; z++) {
        for (int x = 0; x < this.x; x++) {
          int blockInd = (y * this.z + z) * this.x + x;

          int blockID = blocks[blockInd] & 0xFF;
          if (additional != null) {
            blockID |= additional[blockInd] << 8;
          }
          this.states[x][z][y] = BlockStateCodec.defaultState(ItemIdFix.getItem(blockID));
        }
      }
    }
  }
}
