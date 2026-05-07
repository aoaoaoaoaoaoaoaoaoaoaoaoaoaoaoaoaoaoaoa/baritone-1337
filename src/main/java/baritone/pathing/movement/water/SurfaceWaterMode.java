package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.transport.TransportMode;
import java.util.Collection;
import net.minecraft.world.level.block.state.BlockState;

public sealed interface SurfaceWaterMode permits SurfaceWaterMode.Swim, SurfaceWaterMode.Boat {
  boolean legal(CalculationContext context, int x, int y, int z);

  default boolean legalHull(CalculationContext context, int x, int y, int z) {
    return legal(context, x, y, z);
  }

  void appendValidPositions(BetterBlockPos waterCell, Collection<BetterBlockPos> positions);

  TransportMode transportMode();

  default boolean boat() {
    return false;
  }

  record Swim() implements SurfaceWaterMode {
    @Override
    public boolean legal(CalculationContext context, int x, int y, int z) {
      return context.hasPathingData(x, z) && context.worldBorder.entirelyContains(x, z) && MovementHelper.surfaceSwimCell(context, x, y, z);
    }

    @Override
    public void appendValidPositions(BetterBlockPos waterCell, Collection<BetterBlockPos> positions) {
      positions.add(waterCell);
    }

    @Override
    public TransportMode transportMode() {
      return TransportMode.SWIM;
    }
  }

  record Boat() implements SurfaceWaterMode {
    @Override
    public boolean legal(CalculationContext context, int x, int y, int z) {
      BlockState water = context.get(x, y, z);
      BlockState head = context.get(x, y + 1, z);
      BlockState canopy = context.get(x, y + 2, z);
      return context.hasPathingData(x, z) && context.worldBorder.entirelyContains(x, z) && MovementHelper.isWater(water) && MovementHelper.canSwimThrough(context, water)
        && !MovementHelper.isWater(head) && MovementHelper.canMoveThrough(context, x, y + 1, z, head) && MovementHelper.canMoveThrough(context, x, y + 2, z, canopy);
    }

    @Override
    public boolean legalHull(CalculationContext context, int x, int y, int z) {
      BlockState body = context.get(x, y, z);
      BlockState head = context.get(x, y + 1, z);
      BlockState canopy = context.get(x, y + 2, z);
      return context.hasPathingData(x, z) && context.worldBorder.entirelyContains(x, z)
        && (MovementHelper.isWater(body) && MovementHelper.canSwimThrough(context, body) || MovementHelper.canMoveThrough(context, x, y, z, body)) && !MovementHelper.isWater(head)
        && MovementHelper.canMoveThrough(context, x, y + 1, z, head) && MovementHelper.canMoveThrough(context, x, y + 2, z, canopy);
    }

    @Override
    public void appendValidPositions(BetterBlockPos waterCell, Collection<BetterBlockPos> positions) {
      positions.add(waterCell);
      positions.add(waterCell.above());
      positions.add(waterCell.above(2));
    }

    @Override
    public TransportMode transportMode() {
      return TransportMode.BOAT;
    }

    @Override
    public boolean boat() {
      return true;
    }
  }
}
