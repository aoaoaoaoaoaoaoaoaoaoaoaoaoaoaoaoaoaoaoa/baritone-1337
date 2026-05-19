package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import java.util.Optional;

/**
 * A goal whose executable search target is the best certifiable exit from the currently exact graph,
 * scored by an external continuation value.
 */
public interface BestExitGoal extends Goal {
  /**
   * Expected continuation value after handing control from exact local pathing to the abstract planner at this block.
   */
  double exitValue(int x, int y, int z);

  /**
   * True when this exact-graph node is a legal handoff point even though adjacent block data may still be present. This is used when the abstract value field's factual frontier is stricter than the
   * client's loaded-chunk frontier, for example when live macro cells end before the next locally cached chunk edge.
   */
  default boolean isExactExit(int x, int y, int z) {
    return false;
  }

  default Optional<BetterBlockPos> preferredExactExit() {
    return Optional.empty();
  }

  default List<BetterBlockPos> preferredExactPath() {
    return List.of();
  }

  default double exactGoalExitValue(int x, int y, int z) {
    return 0D;
  }
}
