package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.CalculationContext;
import java.util.Optional;
import net.minecraft.core.BlockPos;

final class MacroGoals {
  private MacroGoals() {
  }

  static Optional<BlockPos> pos(Goal goal) {
    return switch (goal) {
      case GoalBlock block -> Optional.of(block.getGoalPos());
      case GoalXZ xz -> Optional.of(new BlockPos(xz.getX(), 64, xz.getZ()));
      case IGoalRenderPos render -> Optional.of(render.getGoalPos());
      default -> Optional.empty();
    };
  }

  static boolean destinationChunkLive(CalculationContext context, Goal goal) {
    return pos(goal).filter(pos -> context.bsi.hasLiveChunk(pos.getX(), pos.getZ())).isPresent();
  }
}
