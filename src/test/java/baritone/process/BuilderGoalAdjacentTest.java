package baritone.process;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

public class BuilderGoalAdjacentTest {
  @Test
  public void allowsStandingBelowAndAdjacentForCeilingPlacements() {
    BuilderProcess.GoalAdjacent goal = new BuilderProcess.GoalAdjacent(new BlockPos(10, 66, 10), new BlockPos(10, 66, 9), false);

    assertTrue(goal.isInGoal(11, 65, 10));
    assertFalse(goal.isInGoal(10, 65, 10));
    assertFalse(goal.isInGoal(11, 64, 10));
  }
}
