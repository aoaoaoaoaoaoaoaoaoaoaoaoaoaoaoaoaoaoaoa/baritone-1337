package baritone.pathing.macro.portal;

import baritone.api.schematic.AbstractSchematic;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class PortalFrameSchematic extends AbstractSchematic {
  private final Direction.Axis axis;

  public PortalFrameSchematic(Direction.Axis axis) {
    super(axis == Direction.Axis.X ? PortalFrame.INNER_WIDTH + 2 : 1, PortalFrame.INNER_HEIGHT + 2, axis == Direction.Axis.Z ? PortalFrame.INNER_WIDTH + 2 : 1);
    if (axis == Direction.Axis.Y) {
      throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
    }
    this.axis = axis;
  }

  public static BetterBlockPos originFor(BetterBlockPos lowerLeftInterior, Direction.Axis axis) {
    return switch (axis) {
      case X -> new BetterBlockPos(lowerLeftInterior.x - 1, lowerLeftInterior.y - 1, lowerLeftInterior.z);
      case Z -> new BetterBlockPos(lowerLeftInterior.x, lowerLeftInterior.y - 1, lowerLeftInterior.z - 1);
      default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
    };
  }

  @Override
  public boolean inSchematic(int x, int y, int z, BlockState currentState) {
    if (!super.inSchematic(x, y, z, currentState)) {
      return false;
    }
    return cellKind(x, y, z) != CellKind.OUTSIDE;
  }

  @Override
  public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
    int horizontal = horizontal(x, z);
    int vertical = y - 1;
    return PortalFrame.requiredFrameOffset(horizontal, vertical) ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
  }

  public Direction.Axis axis() {
    return axis;
  }

  private int horizontal(int x, int z) {
    return axis == Direction.Axis.X ? x - 1 : z - 1;
  }

  CellKind cellKind(int x, int y, int z) {
    int horizontal = horizontal(x, z);
    int vertical = y - 1;
    if (PortalFrame.requiredFrameOffset(horizontal, vertical)) {
      return CellKind.FRAME;
    }
    if (PortalFrame.interiorOffset(horizontal, vertical)) {
      return CellKind.INTERIOR;
    }
    return CellKind.OUTSIDE;
  }

  enum CellKind {
    FRAME, INTERIOR, OUTSIDE
  }
}
