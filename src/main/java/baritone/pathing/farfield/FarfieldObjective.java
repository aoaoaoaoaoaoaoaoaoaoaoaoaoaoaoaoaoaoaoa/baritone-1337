package baritone.pathing.farfield;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.FrontierValueObjective;
import net.minecraft.core.BlockPos;

public final class FarfieldObjective implements FrontierValueObjective {
  private static final double SQRT_2 = Math.sqrt(2D);

  private final Goal finalGoal;
  private final FarfieldSnapshot snapshot;
  private final BetterBlockPos start;
  private final double ordinaryHeuristicTicksPerBlock;
  private final boolean obliqueLowerBound;

  FarfieldObjective(Goal finalGoal, FarfieldSnapshot snapshot, BetterBlockPos start) {
    this.finalGoal = finalGoal;
    this.snapshot = snapshot;
    this.start = start;
    this.ordinaryHeuristicTicksPerBlock = Baritone.settings().costHeuristic.value;
    this.obliqueLowerBound = Baritone.settings().allowObliqueWalk.value;
  }

  @Override
  public boolean isInGoal(int x, int y, int z) {
    return finalGoal.isInGoal(x, y, z);
  }

  @Override
  public double heuristic(int x, int y, int z) {
    if (finalGoal.isInGoal(x, y, z)) {
      return finalGoal.heuristic(x, y, z);
    }
    // Event A* calls this as a proof key factory; exit scoring keeps the richer expected field.
    double floor = snapshot.floorLowerBoundAt(x, y, z);
    double ordinary = ordinaryLowerBound(x, y, z);
    return Double.isFinite(floor) ? Math.min(floor, ordinary) : ordinary;
  }

  @Override
  public double heuristic() {
    return finalGoal.heuristic();
  }

  @Override
  public double frontierExitValue(int sourceX, int sourceY, int sourceZ, int boundaryX, int boundaryY, int boundaryZ) {
    return snapshot.expected(new FrontierExit(sourceX, sourceY, sourceZ, boundaryX, boundaryY, boundaryZ));
  }

  public double expectedObjective(BlockPos pos) {
    return finalGoal.isInGoal(pos) ? finalGoal.heuristic(pos) : snapshot.expectedAt(pos.getX(), pos.getY(), pos.getZ());
  }

  public double expectedObjective(int x, int y, int z) {
    return finalGoal.isInGoal(x, y, z) ? finalGoal.heuristic(x, y, z) : snapshot.expectedAt(x, y, z);
  }

  public double verticalRangeLowerBound(int x, int z, int yMin, int yMax) {
    // Dynamic descents expose a y-envelope; Farfield is only four strata, so do not scan block-y.
    double ordinary = ordinaryLowerBound(x, yMin, z);
    double floor = snapshot.floorLowerBoundOverYRange(x, z, yMin, yMax);
    return Double.isFinite(floor) ? Math.min(floor, ordinary) : ordinary;
  }

  public Goal finalGoal() {
    return finalGoal;
  }

  public FarfieldSnapshot snapshot() {
    return snapshot;
  }

  public double expectedAtStart() {
    return snapshot.expectedAt(start.x, start.y, start.z);
  }

  public int startStratum() {
    return snapshot.stratumAt(start.y);
  }

  public java.util.List<BetterBlockPos> skeleton() {
    return snapshot.skeletonFrom(start);
  }

  private double ordinaryLowerBound(int x, int y, int z) {
    if (finalGoal instanceof GoalXZ xz) {
      int dx = Math.abs(x - xz.getX());
      int dz = Math.abs(z - xz.getZ());
      if (obliqueLowerBound) {
        return Math.max(dx, dz) * ordinaryHeuristicTicksPerBlock;
      }
      int straight = Math.abs(dx - dz);
      int diagonal = Math.min(dx, dz);
      return (straight + diagonal * SQRT_2) * ordinaryHeuristicTicksPerBlock;
    }
    return Math.max(0D, finalGoal.heuristic(x, y, z));
  }

  @Override
  public String toString() {
    return "FarfieldObjective{" + finalGoal + ", sig=" + Long.toUnsignedString(snapshot.signature()) + "}";
  }
}
