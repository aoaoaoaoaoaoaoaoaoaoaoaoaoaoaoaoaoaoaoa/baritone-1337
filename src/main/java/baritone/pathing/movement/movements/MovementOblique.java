package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.control.ControlFrame;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MovementOblique extends Movement {
  private static final double HALF_WIDTH = 0.30000001192092896D;
  private static final double SQRT_5 = Math.sqrt(5);

  private final int dx;
  private final int dz;

  public MovementOblique(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, int dx, int dz) {
    super(baritone, src, dest, new BetterBlockPos[0]);
    this.dx = dx;
    this.dz = dz;
  }

  @Override
  public double calculateCost(CalculationContext context) {
    return cost(context, src.x, src.y, src.z, dx, dz);
  }

  @Override
  protected Set<BetterBlockPos> calculateValidPositions() {
    Set<BetterBlockPos> result = new LinkedHashSet<>();
    forEachSweptCell(src.x, src.z, dx, dz, (x, z) -> result.add(new BetterBlockPos(x, src.y, z)));
    result.add(dest);
    return result;
  }

  public static double cost(CalculationContext context, int x, int y, int z, int dx, int dz) {
    if (!isObliqueStride(dx, dz)) {
      return COST_INF;
    }
    boolean shallowWater = false;
    boolean deepWater = false;
    int minX = minOffsetX(dx);
    int maxX = maxOffsetX(dx);
    int minZ = minOffsetZ(dz);
    int maxZ = maxOffsetZ(dz);
    for (int ox = minX; ox <= maxX; ox++) {
      for (int oz = minZ; oz <= maxZ; oz++) {
        if (segmentIntersectsExpandedCell(ox, oz, dx, dz)) {
          FlatCell cell = clearFlatCell(context, x + ox, y, z + oz);
          if (cell == FlatCell.BLOCKED) {
            return COST_INF;
          }
          shallowWater |= cell == FlatCell.SHALLOW_WATER;
          deepWater |= cell == FlatCell.DEEP_WATER;
        }
      }
    }
    boolean fastSwim = deepWater && MovementHelper.hasSurfaceSwimSpan(context, x, y, z, dx, dz);
    return SQRT_5 * (fastSwim ? context.costs.waterMoveCost() : deepWater || shallowWater ? context.costs.waterWalkCost() : context.movement.canSprint() ? SPRINT_ONE_BLOCK_COST : WALK_ONE_BLOCK_COST);
  }

  @Override
  public ControlFrame.Builder updateState(ControlFrame.Builder state) {
    super.updateState(state);
    if (state.getStatus() != MovementStatus.RUNNING) {
      return state;
    }
    if (playerAtDest() || overshotDestination()) {
      return state.setStatus(MovementStatus.SUCCESS);
    }
    if (!playerInValidPosition()) {
      return state.setStatus(MovementStatus.UNREACHABLE);
    }
    if (Baritone.settings().allowSprint.value && (!MovementHelper.isLiquid(ctx, ctx.playerFeet()) || Baritone.settings().sprintInWater.value)) {
      state.setInput(Input.SPRINT, true);
    }
    MovementHelper.moveTowards(ctx, state, dest);
    return state;
  }

  @Override
  protected boolean prepared(ControlFrame.Builder state) {
    return true;
  }

  private boolean overshotDestination() {
    double sx = src.x + 0.5D;
    double sz = src.z + 0.5D;
    double px = ctx.player().position().x;
    double pz = ctx.player().position().z;
    double lenSq = dx * dx + dz * dz;
    double projection = ((px - sx) * dx + (pz - sz) * dz) / lenSq;
    if (projection < 1D || projection > 1.45D) {
      return false;
    }
    double closestX = sx + dx * projection;
    double closestZ = sz + dz * projection;
    double lateralSq = square(px - closestX) + square(pz - closestZ);
    return lateralSq <= 0.42D * 0.42D;
  }

  private static FlatCell clearFlatCell(CalculationContext context, int x, int y, int z) {
    if (!context.isLoaded(x, z) || !context.worldBorder.entirelyContains(x, z)) {
      return FlatCell.BLOCKED;
    }
    BlockState feet = context.get(x, y, z);
    BlockState head = context.get(x, y + 1, z);
    BlockState support = context.get(x, y - 1, z);
    boolean feetWater = MovementHelper.isWater(feet);
    boolean headWater = MovementHelper.isWater(head);
    if (headWater) {
      return FlatCell.BLOCKED;
    }
    if (MovementHelper.avoidWalkingInto(feet) && !feetWater || MovementHelper.avoidWalkingInto(head) && !headWater) {
      return FlatCell.BLOCKED;
    }
    if (feetWater) {
      if (!MovementHelper.canSwimThrough(context, feet) || !MovementHelper.canMoveThrough(context, x, y + 1, z, head)) {
        return FlatCell.BLOCKED;
      }
      return MovementHelper.isWater(support) || headWater ? FlatCell.DEEP_WATER : FlatCell.SHALLOW_WATER;
    }
    if (!MovementHelper.fullyPassable(context, x, y, z, feet) || !MovementHelper.fullyPassable(context, x, y + 1, z, head)) {
      return FlatCell.BLOCKED;
    }
    if (!support.getFluidState().isEmpty() || support.is(Blocks.MAGMA_BLOCK) || MovementHelper.isLava(support)) {
      return FlatCell.BLOCKED;
    }
    return MovementHelper.canWalkOn(context, x, y - 1, z, support) && (MovementHelper.isBlockNormalCube(support) || MovementHelper.isGlassLike(support)) ? FlatCell.LAND : FlatCell.BLOCKED;
  }

  private enum FlatCell {
    BLOCKED, LAND, SHALLOW_WATER, DEEP_WATER
  }

  private static void forEachSweptCell(int x, int z, int dx, int dz, CellConsumer consumer) {
    int minX = minOffsetX(dx);
    int maxX = maxOffsetX(dx);
    int minZ = minOffsetZ(dz);
    int maxZ = maxOffsetZ(dz);
    for (int ox = minX; ox <= maxX; ox++) {
      for (int oz = minZ; oz <= maxZ; oz++) {
        if (segmentIntersectsExpandedCell(ox, oz, dx, dz)) {
          consumer.accept(x + ox, z + oz);
        }
      }
    }
  }

  private static boolean segmentIntersectsExpandedCell(int cellX, int cellZ, int dx, int dz) {
    double minX = cellX - HALF_WIDTH;
    double maxX = cellX + 1D + HALF_WIDTH;
    double minZ = cellZ - HALF_WIDTH;
    double maxZ = cellZ + 1D + HALF_WIDTH;
    double startX = 0.5D;
    double startZ = 0.5D;
    double endX = dx + 0.5D;
    double endZ = dz + 0.5D;
    double tMin = 0D;
    double tMax = 1D;

    double deltaX = endX - startX;
    if (deltaX == 0D) {
      if (startX < minX || startX > maxX) {
        return false;
      }
    } else {
      double a = (minX - startX) / deltaX;
      double b = (maxX - startX) / deltaX;
      tMin = Math.max(tMin, Math.min(a, b));
      tMax = Math.min(tMax, Math.max(a, b));
      if (tMin > tMax) {
        return false;
      }
    }

    double deltaZ = endZ - startZ;
    if (deltaZ == 0D) {
      return startZ >= minZ && startZ <= maxZ;
    }
    double a = (minZ - startZ) / deltaZ;
    double b = (maxZ - startZ) / deltaZ;
    tMin = Math.max(tMin, Math.min(a, b));
    tMax = Math.min(tMax, Math.max(a, b));
    return tMin <= tMax;
  }

  private static int minOffsetX(int dx) {
    return (int) Math.floor(Math.min(0.5D, dx + 0.5D) - HALF_WIDTH);
  }

  private static int maxOffsetX(int dx) {
    return (int) Math.floor(Math.max(0.5D, dx + 0.5D) + HALF_WIDTH);
  }

  private static int minOffsetZ(int dz) {
    return (int) Math.floor(Math.min(0.5D, dz + 0.5D) - HALF_WIDTH);
  }

  private static int maxOffsetZ(int dz) {
    return (int) Math.floor(Math.max(0.5D, dz + 0.5D) + HALF_WIDTH);
  }

  private static boolean isObliqueStride(int dx, int dz) {
    int ax = Math.abs(dx);
    int az = Math.abs(dz);
    return ax + az == 3 && ax > 0 && az > 0;
  }

  private static double square(double value) {
    return value * value;
  }

  @FunctionalInterface
  private interface CellConsumer {
    void accept(int x, int z);
  }
}
