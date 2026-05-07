package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.pathing.calc.BestExitGoal;
import net.minecraft.core.BlockPos;

public final class MacroProjectedGoal implements BestExitGoal {
  private final Goal finalGoal;
  private final MacroValueField field;

  public MacroProjectedGoal(Goal finalGoal, MacroValueField field) {
    this.finalGoal = finalGoal;
    this.field = field;
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
    double floor = field.floorAtBlock(x, y, z);
    double ordinary = finalGoal.heuristic(x, y, z);
    return Double.isFinite(floor) ? Math.min(floor, ordinary) : ordinary;
  }

  @Override
  public double heuristic() {
    return finalGoal.heuristic();
  }

  @Override
  public double exitValue(int x, int y, int z) {
    return field.expectedAtBlock(x, y, z);
  }

  public double expectedObjective(BlockPos pos) {
    return finalGoal.isInGoal(pos) ? finalGoal.heuristic(pos) : exitValue(pos.getX(), pos.getY(), pos.getZ());
  }

  public Goal finalGoal() {
    return finalGoal;
  }

  @Override
  public String toString() {
    return "MacroProjectedGoal{" + finalGoal + "}";
  }
}
