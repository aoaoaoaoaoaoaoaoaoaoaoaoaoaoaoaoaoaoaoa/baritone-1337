package baritone.pathing.goals;

import baritone.api.pathing.goals.GoalNearXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.goal.GoalTerminalPolicy;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalNearXZTest {
  @Test
  public void acceptsHorizontalDiskWithoutConstrainingY() {
    GoalNearXZ goal = new GoalNearXZ(10, -20, 3);
    assertTrue(goal.isInGoal(13, -64, -20));
    assertTrue(goal.isInGoal(12, 320, -18));
    assertFalse(goal.isInGoal(14, 0, -20));
  }

  @Test
  public void plannedHorseTerminalPredicateKeepsLiveCenterMargin() {
    GoalNearXZ goal = new GoalNearXZ(10, -20, 3);
    int firstOutsidePlanningDisk = (int) Math.ceil(GoalTerminalPolicy.horseGoalXZPlanningRadius(goal, GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS));
    int lastInsidePlanningDisk = Math.max(0, firstOutsidePlanningDisk - 1);
    assertFalse(GoalTerminalPolicy.plannedHorseSatisfied(goal, new BetterBlockPos(goal.getX() + firstOutsidePlanningDisk, 80, goal.getZ())));
    assertTrue(GoalTerminalPolicy.plannedHorseSatisfied(goal, new BetterBlockPos(goal.getX() + lastInsidePlanningDisk, 80, goal.getZ())));
  }
}
