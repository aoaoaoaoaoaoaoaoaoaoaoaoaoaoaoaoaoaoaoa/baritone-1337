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
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
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
  public static final int HORSE_GOAL_XZ_RADIUS = 2;
  public static final int HORSE_OBSTRUCTED_GOAL_XZ_RADIUS = 5;
  public static final double HORSE_GOAL_XZ_PLANNING_MARGIN = 1.15D;
  private static final double HULL_EPSILON = 1.0E-4D;
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
    if (ctx.player().getVehicle() instanceof AbstractHorse horse && goal instanceof GoalXZ xz) {
      return horseGoalXZSatisfied(horse, xz, horseGoalXZRadius(ctx, horse, xz));
    }
    if (unsafeSwimGoal(baritone, ctx, goal)) {
      Optional<BetterBlockPos> egress = egress(ctx, (GoalXZ) goal);
      return egress.map(pos::equals).orElseGet(() -> goal.isInGoal(pos));
    }
    return goal.isInGoal(pos);
  }

  public static boolean plannedHorseSatisfied(Goal goal, BetterBlockPos pos) {
    if (goal == null) {
      return true;
    }
    if (goal instanceof GoalXZ xz) {
      double radius = horseGoalXZPlanningRadius(xz, HORSE_GOAL_XZ_RADIUS);
      return horseGoalXZBlockCenterSatisfied(pos, xz, radius);
    }
    if (goal.isInGoal(pos)) {
      return true;
    }
    return false;
  }

  public static double horseGoalXZPlanningRadius(GoalXZ goal, int policyRadius) {
    return Math.max(0D, Math.max(goal.xzRadius(), policyRadius) - HORSE_GOAL_XZ_PLANNING_MARGIN);
  }

  private static boolean unsafeSwimGoal(CalculationContext context, Goal goal) {
    return goal instanceof GoalXZ && !(context.getBaritone().getPlayerContext().player().getVehicle() instanceof AbstractHorse) && !context.waterTransport.boatAvailable()
      && !context.waterTransport.boatMounted();
  }

  private static boolean unsafeSwimGoal(Baritone baritone, IPlayerContext ctx, Goal goal) {
    if (!(goal instanceof GoalXZ)) {
      return false;
    }
    WaterTransportPolicy transport = WaterTransportPolicy.snapshot(baritone);
    return !transport.boatAvailable() && !transport.boatMounted() && ctx.player().getVehicle() == null;
  }

  private static int horseGoalXZRadius(IPlayerContext ctx, AbstractHorse horse, GoalXZ goal) {
    int mountedRadius = horseGoalXZObstructed(ctx, horse, goal) ? HORSE_OBSTRUCTED_GOAL_XZ_RADIUS : HORSE_GOAL_XZ_RADIUS;
    return Math.max(goal.xzRadius(), mountedRadius);
  }

  private static boolean horseGoalXZSatisfied(Entity horse, GoalXZ goal, int radiusBlocks) {
    double dx = horse.getX() - goal.getX();
    double dz = horse.getZ() - goal.getZ();
    return dx * dx + dz * dz <= radiusBlocks * radiusBlocks;
  }

  private static boolean horseGoalXZBlockCenterSatisfied(BetterBlockPos pos, GoalXZ goal, double radiusBlocks) {
    double dx = pos.x - goal.getX();
    double dz = pos.z - goal.getZ();
    return dx * dx + dz * dz <= radiusBlocks * radiusBlocks;
  }

  private static boolean horseGoalXZObstructed(IPlayerContext ctx, AbstractHorse horse, GoalXZ goal) {
    BlockStateInterface bsi = new BlockStateInterface(ctx);
    boolean known = false;
    int horseFeetY = Mth.floor(horse.getBoundingBox().minY + 0.01D);
    int minY = Math.max(ctx.world().dimensionType().minY() + 1, horseFeetY - 2);
    int maxY = Math.min(ctx.world().dimensionType().minY() + ctx.world().dimensionType().height() - 2, horseFeetY + 2);
    for (int x = goal.getX() - HORSE_GOAL_XZ_RADIUS; x <= goal.getX() + HORSE_GOAL_XZ_RADIUS; x++) {
      for (int z = goal.getZ() - HORSE_GOAL_XZ_RADIUS; z <= goal.getZ() + HORSE_GOAL_XZ_RADIUS; z++) {
        if (!bsi.hasPathingData(x, z)) {
          continue;
        }
        known = true;
        for (int y = minY; y <= maxY; y++) {
          if (horseStandable(bsi, horse, x, y, z)) {
            return false;
          }
        }
      }
    }
    return known;
  }

  private static boolean horseStandable(BlockStateInterface bsi, AbstractHorse horse, int x, int y, int z) {
    return horseClearHull(bsi, horse, x + 0.5D, y, z + 0.5D) && (horseSolidSupport(bsi, x, y, z) || horseWaterSupport(bsi, x, y, z));
  }

  private static boolean horseClearHull(BlockStateInterface bsi, AbstractHorse horse, double centerX, int feetY, double centerZ) {
    double halfWidth = horse.getBbWidth() * 0.5D;
    int minX = Mth.floor(centerX - halfWidth + HULL_EPSILON);
    int maxX = Mth.floor(centerX + halfWidth - HULL_EPSILON);
    int minY = Mth.floor(feetY + HULL_EPSILON);
    int maxY = Mth.floor(feetY + horse.getBbHeight() - HULL_EPSILON);
    int minZ = Mth.floor(centerZ - halfWidth + HULL_EPSILON);
    int maxZ = Mth.floor(centerZ + halfWidth - HULL_EPSILON);
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (!horsePassable(bsi, pos.set(x, y, z), bsi.get0(x, y, z))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean horsePassable(BlockStateInterface bsi, BlockPos.MutableBlockPos pos, BlockState state) {
    return state.getFluidState().is(FluidTags.WATER) || state.getFluidState().isEmpty() && state.getCollisionShape(bsi.access, pos).isEmpty();
  }

  private static boolean horseSolidSupport(BlockStateInterface bsi, int x, int y, int z) {
    BlockState support = bsi.get0(x, y - 1, z);
    return !support.is(BlockTags.LEAVES) && support.getFluidState().isEmpty() && !support.getCollisionShape(bsi.access, new BlockPos(x, y - 1, z)).isEmpty();
  }

  private static boolean horseWaterSupport(BlockStateInterface bsi, int x, int y, int z) {
    return bsi.get0(x, y, z).getFluidState().is(FluidTags.WATER) || bsi.get0(x, y - 1, z).getFluidState().is(FluidTags.WATER);
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
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = context.world.getMaxY() - 2; y >= context.world.getMinY() + 1; y--) {
      if (MovementHelper.surfaceSwimCell(context, x, y, z)) {
        return OptionalInt.of(y);
      }
      if (surfaceColumnCapped(context.bsi, pos.set(x, y, z), context.get(x, y, z))) {
        return OptionalInt.empty();
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
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = maxY - 2; y >= minY + 1; y--) {
      BlockState feet = bsi.get0(x, y, z);
      if (MovementHelper.isWater(feet) && MovementHelper.isWater(bsi.get0(x, y - 1, z)) && !MovementHelper.isWater(bsi.get0(x, y + 1, z)) && MovementHelper.canSwimThrough(feet)) {
        return OptionalInt.of(y);
      }
      if (surfaceColumnCapped(bsi, pos.set(x, y, z), feet)) {
        return OptionalInt.empty();
      }
    }
    return OptionalInt.empty();
  }

  private static boolean surfaceColumnCapped(BlockStateInterface bsi, BlockPos pos, BlockState state) {
    return !state.is(BlockTags.LEAVES) && !state.getFluidState().is(FluidTags.WATER) && (!state.getFluidState().isEmpty() || !state.getCollisionShape(bsi.access, pos).isEmpty());
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
