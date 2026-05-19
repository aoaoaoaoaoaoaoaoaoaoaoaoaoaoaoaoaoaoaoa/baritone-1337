package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.LocalExitObjective;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class ValueProjectedExitObjective implements LocalExitObjective {
  private final Goal finalGoal;
  private final MacroValueField field;

  public ValueProjectedExitObjective(Goal finalGoal, MacroValueField field) {
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
    double floor = field.admissibleFloorAtBlock(x, y, z);
    double ordinary = finalGoal.heuristic(x, y, z);
    return Double.isFinite(floor) ? Math.min(floor, ordinary) : ordinary;
  }

  @Override
  public double heuristic() {
    return finalGoal.heuristic();
  }

  @Override
  public double localExitValue(int x, int y, int z) {
    return field.expectedContinuationAtBlock(x, y, z);
  }

  @Override
  public boolean isExactLocalExit(int x, int y, int z) {
    return field.exactLocalExitAtBlock(x, y, z);
  }

  @Override
  public Optional<BetterBlockPos> preferredLocalExit() {
    return field.preferredLocalExit();
  }

  @Override
  public List<BetterBlockPos> preferredLocalExitPath() {
    return field.preferredLocalExitPath();
  }

  public double expectedObjective(BlockPos pos) {
    return finalGoal.isInGoal(pos) ? finalGoal.heuristic(pos) : localExitValue(pos.getX(), pos.getY(), pos.getZ());
  }

  public Goal finalGoal() {
    return finalGoal;
  }

  @Override
  public String toString() {
    return "ValueProjectedExitObjective{" + finalGoal + "}";
  }
}
