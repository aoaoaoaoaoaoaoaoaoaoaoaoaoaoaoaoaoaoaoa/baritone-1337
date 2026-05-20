package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.Baritone;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.PedestrianLavaProximity;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MovementCruiseRay extends Movement {
  private static final double HALF_WIDTH = 0.30000001192092896D;
  private static final double OVERSHOOT_BLOCKS = 0.8D;
  private static final double TUBE_TOLERANCE = 1.25D;

  private final int dx;
  private final int dz;
  private final double length;

  public MovementCruiseRay(IBaritone baritone, BetterBlockPos src, int dx, int dz) {
    super(baritone, src, new BetterBlockPos(src.x + dx, src.y, src.z + dz), new BetterBlockPos[0]);
    this.dx = dx;
    this.dz = dz;
    this.length = Math.hypot(dx, dz);
  }

  @Override
  public double calculateCost(CalculationContext context) {
    return cost(context, src.x, src.y, src.z, dx, dz);
  }

  @Override
  protected Set<BetterBlockPos> calculateValidPositions() {
    LinkedHashSet<BetterBlockPos> result = new LinkedHashSet<>();
    int gcd = gcd(Math.abs(dx), Math.abs(dz));
    int sx = dx / gcd;
    int sz = dz / gcd;
    for (int i = 0; i <= gcd; i++) {
      result.add(new BetterBlockPos(src.x + sx * i, src.y, src.z + sz * i));
    }
    return result;
  }

  @Override
  public boolean acceptsPathingDrift(BlockPos pos) {
    if (pos.getY() != src.y) {
      return false;
    }
    double progress = progress(ctx.player().position().x, ctx.player().position().z);
    if (progress < -0.25D || progress > 1D + OVERSHOOT_BLOCKS / Math.max(1D, length)) {
      return false;
    }
    return lateralDistanceSq(ctx.player().position().x, ctx.player().position().z, progress) <= TUBE_TOLERANCE * TUBE_TOLERANCE;
  }

  @Override
  public double sustainedPathDistanceTolerance() {
    return TUBE_TOLERANCE;
  }

  @Override
  public double immediatePathDistanceTolerance() {
    return TUBE_TOLERANCE + 0.75D;
  }

  public static double cost(CalculationContext context, int x, int y, int z, int dx, int dz) {
    if (!validRay(dx, dz)) {
      return COST_INF;
    }
    int minX = minOffsetX(dx);
    int maxX = maxOffsetX(dx);
    int minZ = minOffsetZ(dz);
    int maxZ = maxOffsetZ(dz);
    for (int ox = minX; ox <= maxX; ox++) {
      for (int oz = minZ; oz <= maxZ; oz++) {
        if (segmentIntersectsExpandedCell(ox, oz, dx, dz) && !clearDryCruiseCell(context, x + ox, y, z + oz)) {
          return COST_INF;
        }
      }
    }
    int gcd = gcd(Math.abs(dx), Math.abs(dz));
    int sx = dx / gcd;
    int sz = dz / gcd;
    double lavaProximityPenalty = 0D;
    for (int i = 1; i <= gcd; i++) {
      lavaProximityPenalty += PedestrianLavaProximity.arrivalPenalty(context, x + sx * (i - 1), y, z + sz * (i - 1), x + sx * i, y, z + sz * i);
    }
    double stepCost = context.movement.canSprint() ? SPRINT_ONE_BLOCK_COST : WALK_ONE_BLOCK_COST;
    double length = Math.hypot(dx, dz);
    double dividend = Math.max(0D, Baritone.settings().pedestrianCruiseRayBoundaryDividend.value) * Math.max(0, gcd - 1);
    return Math.max(stepCost, length * stepCost - dividend) + lavaProximityPenalty;
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
    if (!playerInValidPosition() && !acceptsPathingDrift(ctx.playerFeet())) {
      return state.setStatus(MovementStatus.UNREACHABLE);
    }
    if (Baritone.settings().allowSprint.value) {
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
    double progress = progress(ctx.player().position().x, ctx.player().position().z);
    if (progress < 1D || progress > 1D + OVERSHOOT_BLOCKS / Math.max(1D, length)) {
      return false;
    }
    return lateralDistanceSq(ctx.player().position().x, ctx.player().position().z, progress) <= 0.65D * 0.65D;
  }

  private double progress(double x, double z) {
    double sx = src.x + 0.5D;
    double sz = src.z + 0.5D;
    double lenSq = dx * dx + dz * dz;
    return ((x - sx) * dx + (z - sz) * dz) / lenSq;
  }

  private double lateralDistanceSq(double x, double z, double progress) {
    double sx = src.x + 0.5D;
    double sz = src.z + 0.5D;
    double closestX = sx + dx * progress;
    double closestZ = sz + dz * progress;
    return square(x - closestX) + square(z - closestZ);
  }

  private static boolean clearDryCruiseCell(CalculationContext context, int x, int y, int z) {
    if (!context.hasPathingData(x, z) || !context.worldBorder.entirelyContains(x, z)) {
      return false;
    }
    BlockState feet = context.get(x, y, z);
    BlockState head = context.get(x, y + 1, z);
    BlockState support = context.get(x, y - 1, z);
    if (!MovementHelper.fullyPassable(context, x, y, z, feet) || !MovementHelper.fullyPassable(context, x, y + 1, z, head)) {
      return false;
    }
    if (!support.getFluidState().isEmpty() || support.is(Blocks.MAGMA_BLOCK) || MovementHelper.isLava(support)) {
      return false;
    }
    return MovementHelper.canWalkOn(context, x, y - 1, z, support) && (MovementHelper.isBlockNormalCube(support) || MovementHelper.isGlassLike(support));
  }

  static boolean validRay(int dx, int dz) {
    int ax = Math.abs(dx);
    int az = Math.abs(dz);
    if (ax == 0 && az == 0) {
      return false;
    }
    if (ax == 0 || az == 0) {
      return ax + az >= 2;
    }
    return ax == az && ax >= 2;
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

  private static int gcd(int a, int b) {
    while (b != 0) {
      int t = a % b;
      a = b;
      b = t;
    }
    return Math.max(1, a);
  }

  private static double square(double value) {
    return value * value;
  }
}
