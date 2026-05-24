package baritone.pathing.movement;

import static baritone.api.pathing.movement.ActionCosts.SPRINT_ONE_BLOCK_COST;
import static baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PedestrianLavaProximityTest {
  @Test
  public void analyticalPenaltyBarelyBeatsTwoExtraSprintSteps() {
    double penalty = PedestrianLavaProximity.analyticalPenalty(true);
    assertTrue(penalty > 2D * SPRINT_ONE_BLOCK_COST);
    assertEquals(Math.nextUp(2D * SPRINT_ONE_BLOCK_COST), penalty, 0D);
  }

  @Test
  public void analyticalPenaltyTracksWalkWhenSprintIsUnavailable() {
    double penalty = PedestrianLavaProximity.analyticalPenalty(false);
    assertTrue(penalty > 2D * WALK_ONE_BLOCK_COST);
    assertEquals(Math.nextUp(2D * WALK_ONE_BLOCK_COST), penalty, 0D);
  }
}
