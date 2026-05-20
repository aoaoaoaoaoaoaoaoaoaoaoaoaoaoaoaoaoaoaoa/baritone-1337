package baritone.pathing.movement;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import baritone.api.utils.TrailReversibilityMode;
import org.junit.Test;

public class TrailReversibilityPolicyTest {

  @Test
  public void offLeavesEveryEdgeCostAlone() {
    TrailReversibilityPolicy policy = new TrailReversibilityPolicy(TrailReversibilityMode.OFF, 40D, 200D);
    assertEquals(5D, policy.recost(TrailReversibility.INTRINSIC, 5D), 0D);
    assertEquals(5D, policy.recost(TrailReversibility.SUSPECT, 5D), 0D);
    assertEquals(5D, policy.recost(TrailReversibility.IRREVERSIBLE, 5D), 0D);
  }

  @Test
  public void preferPenalizesButDoesNotForbidWeakEdges() {
    TrailReversibilityPolicy policy = new TrailReversibilityPolicy(TrailReversibilityMode.PREFER, 40D, 200D);
    assertEquals(5D, policy.recost(TrailReversibility.INTRINSIC, 5D), 0D);
    assertEquals(45D, policy.recost(TrailReversibility.SUSPECT, 5D), 0D);
    assertEquals(205D, policy.recost(TrailReversibility.IRREVERSIBLE, 5D), 0D);
  }

  @Test
  public void requireForbidsSuspectAndIrreversibleEdges() {
    TrailReversibilityPolicy policy = new TrailReversibilityPolicy(TrailReversibilityMode.REQUIRE, 40D, 200D);
    assertEquals(5D, policy.recost(TrailReversibility.INTRINSIC, 5D), 0D);
    assertTrue(policy.recost(TrailReversibility.SUSPECT, 5D) >= COST_INF);
    assertTrue(policy.recost(TrailReversibility.IRREVERSIBLE, 5D) >= COST_INF);
  }
}
