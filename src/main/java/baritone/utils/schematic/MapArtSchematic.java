package baritone.utils.schematic;

import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;

public class MapArtSchematic extends MaskSchematic {

  private final int[][] heightMap;

  public MapArtSchematic(IStaticSchematic schematic) {
    super(schematic);
    this.heightMap = generateHeightMap(schematic);
  }

  @Override
  protected boolean partOfMask(int x, int y, int z, BlockState currentState) {
    return y >= this.heightMap[x][z];
  }

  private static int[][] generateHeightMap(IStaticSchematic schematic) {
    int[][] heightMap = new int[schematic.widthX()][schematic.lengthZ()];

    int missingColumns = 0;
    for (int x = 0; x < schematic.widthX(); x++) {
      for (int z = 0; z < schematic.lengthZ(); z++) {
        BlockState[] column = schematic.getColumn(x, z);
        OptionalInt lowestBlockY = lastIndexMatching(column, state -> !(state.getBlock() instanceof AirBlock));
        if (lowestBlockY.isPresent()) {
          heightMap[x][z] = lowestBlockY.getAsInt();
        } else {
          missingColumns++;
          heightMap[x][z] = Integer.MAX_VALUE;
        }
      }
    }
    if (missingColumns != 0) {
      System.out.println(missingColumns + " columns had no block despite being in a map art, letting them be whatever");
    }
    return heightMap;
  }

  private static <T> OptionalInt lastIndexMatching(T[] arr, Predicate<? super T> predicate) {
    for (int y = arr.length - 1; y >= 0; y--) {
      if (predicate.test(arr[y])) {
        return OptionalInt.of(y);
      }
    }
    return OptionalInt.empty();
  }
}
