package baritone.pathing.goal;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.water.WaterTransportPolicy;
import baritone.utils.BlockStateInterface;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Separates geometric goal predicates from safe quiescence predicates.
 *
 * GoalBlock is exact and sacrosanct. GoalXZ is intentionally coarser; if a no-boat route would otherwise
 * terminate by passively floating at a known surface-water XZ, convert that terminal into the nearest
 * factual dry egress within a small shore radius. Deep-ocean swims remain literal.
 */
public final class GoalTerminalPolicy {
  private static final int SWIM_EGRESS_RADIUS = 20;
  private static final int[] DRY_Y_OFFSETS_FROM_WATER = {0, 1, 2};

  private GoalTerminalPolicy() {
  }

  public static Goal project(CalculationContext context, Goal goal) {
    return unsafeSwimGoal(context, goal) ? egress(context, (GoalXZ) goal).<Goal>map(GoalBlock::new).orElse(goal) : goal;
  }

  public static boolean satisfied(Baritone baritone, IPlayerContext ctx, Goal goal, BetterBlockPos pos) {
    if (goal == null) {
      return true;
    }
    if (unsafeSwimGoal(baritone, ctx, goal)) {
      Optional<BetterBlockPos> egress = egress(ctx, (GoalXZ) goal);
      return egress.map(pos::equals).orElseGet(() -> goal.isInGoal(pos));
    }
    return goal.isInGoal(pos);
  }

  private static boolean unsafeSwimGoal(CalculationContext context, Goal goal) {
    return goal instanceof GoalXZ && !context.waterTransport.boatAvailable() && !context.waterTransport.boatMounted();
  }

  private static boolean unsafeSwimGoal(Baritone baritone, IPlayerContext ctx, Goal goal) {
    if (!(goal instanceof GoalXZ)) {
      return false;
    }
    WaterTransportPolicy transport = WaterTransportPolicy.snapshot(baritone);
    return !transport.boatAvailable() && !transport.boatMounted() && ctx.player().getVehicle() == null;
  }

  private static Optional<BetterBlockPos> egress(CalculationContext context, GoalXZ goal) {
    return surfaceWaterY(context, goal.getX(), goal.getZ()).stream().boxed().flatMap(waterY -> nearestDryEgress(context, goal.getX(), waterY, goal.getZ()).stream()).findFirst();
  }

  private static Optional<BetterBlockPos> egress(IPlayerContext ctx, GoalXZ goal) {
    BlockStateInterface bsi = new BlockStateInterface(ctx);
    return surfaceWaterY(ctx, bsi, goal.getX(), goal.getZ()).stream().boxed().flatMap(waterY -> nearestDryEgress(bsi, goal.getX(), waterY, goal.getZ()).stream()).findFirst();
  }

  private static OptionalInt surfaceWaterY(CalculationContext context, int x, int z) {
    if (!context.hasPathingData(x, z)) {
      return OptionalInt.empty();
    }
    for (int y = context.world.getMaxY() - 2; y >= context.world.getMinY() + 1; y--) {
      if (MovementHelper.surfaceSwimCell(context, x, y, z)) {
        return OptionalInt.of(y);
      }
    }
    return OptionalInt.empty();
  }

  private static OptionalInt surfaceWaterY(IPlayerContext ctx, BlockStateInterface bsi, int x, int z) {
    if (!bsi.hasPathingData(x, z)) {
      return OptionalInt.empty();
    }
    int minY = ctx.world().dimensionType().minY();
    int maxY = minY + ctx.world().dimensionType().height();
    for (int y = maxY - 2; y >= minY + 1; y--) {
      BlockState feet = bsi.get0(x, y, z);
      if (MovementHelper.isWater(feet) && MovementHelper.isWater(bsi.get0(x, y - 1, z)) && !MovementHelper.isWater(bsi.get0(x, y + 1, z)) && MovementHelper.canSwimThrough(feet)) {
        return OptionalInt.of(y);
      }
    }
    return OptionalInt.empty();
  }

  private static Optional<BetterBlockPos> nearestDryEgress(CalculationContext context, int waterX, int waterY, int waterZ) {
    BetterBlockPos best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int r = 1; r <= SWIM_EGRESS_RADIUS; r++) {
      for (int x = waterX - r; x <= waterX + r; x++) {
        best = choose(context, waterX, waterY, waterZ, x, waterZ - r, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
        best = choose(context, waterX, waterY, waterZ, x, waterZ + r, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
      }
      for (int z = waterZ - r + 1; z <= waterZ + r - 1; z++) {
        best = choose(context, waterX, waterY, waterZ, waterX - r, z, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
        best = choose(context, waterX, waterY, waterZ, waterX + r, z, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
      }
      if (best != null) {
        return Optional.of(best);
      }
    }
    return Optional.empty();
  }

  private static Optional<BetterBlockPos> nearestDryEgress(BlockStateInterface bsi, int waterX, int waterY, int waterZ) {
    BetterBlockPos best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int r = 1; r <= SWIM_EGRESS_RADIUS; r++) {
      for (int x = waterX - r; x <= waterX + r; x++) {
        best = choose(bsi, waterX, waterY, waterZ, x, waterZ - r, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
        best = choose(bsi, waterX, waterY, waterZ, x, waterZ + r, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
      }
      for (int z = waterZ - r + 1; z <= waterZ + r - 1; z++) {
        best = choose(bsi, waterX, waterY, waterZ, waterX - r, z, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
        best = choose(bsi, waterX, waterY, waterZ, waterX + r, z, best, bestDistance);
        bestDistance = squaredDistance(waterX, waterZ, best);
      }
      if (best != null) {
        return Optional.of(best);
      }
    }
    return Optional.empty();
  }

  private static BetterBlockPos choose(CalculationContext context, int waterX, int waterY, int waterZ, int x, int z, BetterBlockPos incumbent, int incumbentDistance) {
    if (!context.hasPathingData(x, z)) {
      return incumbent;
    }
    for (int dy : DRY_Y_OFFSETS_FROM_WATER) {
      int y = waterY + dy;
      int distance = squaredDistance(waterX, waterZ, x, z);
      if (distance < incumbentDistance && dryStable(context, x, y, z)) {
        return new BetterBlockPos(x, y, z);
      }
    }
    return incumbent;
  }

  private static BetterBlockPos choose(BlockStateInterface bsi, int waterX, int waterY, int waterZ, int x, int z, BetterBlockPos incumbent, int incumbentDistance) {
    if (!bsi.hasPathingData(x, z)) {
      return incumbent;
    }
    for (int dy : DRY_Y_OFFSETS_FROM_WATER) {
      int y = waterY + dy;
      int distance = squaredDistance(waterX, waterZ, x, z);
      if (distance < incumbentDistance && dryStable(bsi, x, y, z)) {
        return new BetterBlockPos(x, y, z);
      }
    }
    return incumbent;
  }

  private static int squaredDistance(int x0, int z0, BetterBlockPos pos) {
    return pos == null ? Integer.MAX_VALUE : squaredDistance(x0, z0, pos.x, pos.z);
  }

  private static int squaredDistance(int x0, int z0, int x1, int z1) {
    int dx = x1 - x0;
    int dz = z1 - z0;
    return dx * dx + dz * dz;
  }

  private static boolean dryStable(CalculationContext context, int x, int y, int z) {
    BlockState feet = context.get(x, y, z);
    return !MovementHelper.isWater(feet) && MovementHelper.canWalkOn(context, x, y - 1, z) && MovementHelper.canWalkThrough(context, x, y, z, feet)
      && MovementHelper.canWalkThrough(context, x, y + 1, z);
  }

  private static boolean dryStable(BlockStateInterface bsi, int x, int y, int z) {
    BlockState feet = bsi.get0(x, y, z);
    return !MovementHelper.isWater(feet) && MovementHelper.canWalkOn(bsi, x, y - 1, z) && MovementHelper.canWalkThrough(bsi, x, y, z, feet) && MovementHelper.canWalkThrough(bsi, x, y + 1, z);
  }
}
