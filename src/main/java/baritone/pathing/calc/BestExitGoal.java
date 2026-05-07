package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;

/**
 * A goal whose executable search target is the best certifiable exit from the currently exact graph,
 * scored by an external continuation value.
 */
public interface BestExitGoal extends Goal {
  /**
   * Expected continuation value after handing control from exact local pathing to the abstract planner at this block.
   */
  double exitValue(int x, int y, int z);

  default double exactGoalExitValue(int x, int y, int z) {
    return 0D;
  }
}
