package baritone.pathing.farfield;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.FrontierValueObjective;
import net.minecraft.core.BlockPos;

public final class FarfieldObjective implements FrontierValueObjective {
  private final Goal finalGoal;
  private final FarfieldSnapshot snapshot;
  private final BetterBlockPos start;

  FarfieldObjective(Goal finalGoal, FarfieldSnapshot snapshot, BetterBlockPos start) {
    this.finalGoal = finalGoal;
    this.snapshot = snapshot;
    this.start = start;
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
    double floor = snapshot.floorAt(x, y, z);
    double ordinary = finalGoal.heuristic(x, y, z);
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

  @Override
  public String toString() {
    return "FarfieldObjective{" + finalGoal + ", sig=" + Long.toUnsignedString(snapshot.signature()) + "}";
  }
}
