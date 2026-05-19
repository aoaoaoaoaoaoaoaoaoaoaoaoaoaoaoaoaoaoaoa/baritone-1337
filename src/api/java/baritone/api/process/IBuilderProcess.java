package baritone.api.process;

import baritone.api.schematic.ISchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * @author Brady
 * @since 1/15/2019
 */
public interface IBuilderProcess extends IBaritoneProcess {

  /**
   * Requests a build for the specified schematic, labeled as specified, with the specified origin.
   *
   * @param name      A user-friendly name for the schematic
   * @param schematic The object representation of the schematic
   * @param origin    The origin position of the schematic being built
   */
  void build(String name, ISchematic schematic, Vec3i origin);

  /**
   * Requests a build for the specified schematic with build-local repeat semantics. {@code repeatCount == -1}
   * repeats forever; otherwise it counts completed placements of this schematic, so {@code repeatCount == 1} means
   * build exactly once at {@code origin}.
   *
   * @param name         A user-friendly name for the schematic
   * @param schematic    The object representation of the schematic
   * @param origin       The origin position of the first schematic being built
   * @param repeat       The world-space vector between successive origins
   * @param repeatCount  The number of repeated builds to execute, or {@code -1} for unbounded repetition
   * @param repeatSneaky Whether the schematic should skip {@link ISchematic#reset()} between repeats
   */
  void build(String name, ISchematic schematic, Vec3i origin, Vec3i repeat, int repeatCount, boolean repeatSneaky);

  /**
   * Requests a build for the specified schematic, labeled as specified, with the specified origin.
   *
   * @param name      A user-friendly name for the schematic
   * @param schematic The file path of the schematic
   * @param origin    The origin position of the schematic being built
   * @return Whether or not the schematic was able to load from file
   */
  boolean build(String name, File schematic, Vec3i origin);

  @Deprecated
  default boolean build(String schematicFile, BlockPos origin) {
    File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), schematicFile);
    return build(schematicFile, file, origin);
  }

  void pause();

  boolean isPaused();

  void resume();

  void clearArea(BlockPos corner1, BlockPos corner2);

  /**
   * Builds a minimal ten-obsidian Nether portal frame and clears the six interior blocks.
   *
   * @param lowerLeftInterior The lower-left interior portal block in world coordinates
   * @param axis              The horizontal axis spanned by the two-wide portal interior
   */
  void buildPortalFrame(BlockPos lowerLeftInterior, Direction.Axis axis);

  /**
   * @return A list of block states that are estimated to be placeable by this builder process. You can use this in
   * schematics, for example, to pick a state that the builder process will be happy with, because any variation will
   * cause it to give up. This is updated every tick, but only while the builder process is active.
   */
  List<BlockState> getApproxPlaceable();

  /**
   * Returns the lower bound of the current mining layer if mineInLayers is true.
   * If mineInLayers is false, this will return an empty optional.
   * @return The lower bound of the current mining layer
   */
  Optional<Integer> getMinLayer();

  /**
   * Returns the upper bound of the current mining layer if mineInLayers is true.
   * If mineInLayers is false, this will return an empty optional.
   * @return The upper bound of the current mining layer
   */
  Optional<Integer> getMaxLayer();
}
