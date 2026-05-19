package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import java.util.Optional;

/**
 * A terminal-goal wrapper that lets local A* stop at the best certifiable exit from the currently exact graph, scored by an external continuation value.
 */
public interface LocalExitObjective extends Goal {
  /**
   * Expected continuation value after handing control from exact local pathing to the abstract planner at this block.
   */
  double localExitValue(int x, int y, int z);

  /**
   * True when this exact-graph node is a legal handoff point even though adjacent block data may still be present. This is used when the abstract value field's factual frontier is stricter than the
   * client's loaded-chunk frontier, for example when live macro cells end before the next locally cached chunk edge.
   */
  default boolean isExactLocalExit(int x, int y, int z) {
    return false;
  }

  default Optional<BetterBlockPos> preferredLocalExit() {
    return Optional.empty();
  }

  default List<BetterBlockPos> preferredLocalExitPath() {
    return List.of();
  }

  default double terminalExitValue(int x, int y, int z) {
    return 0D;
  }
}
