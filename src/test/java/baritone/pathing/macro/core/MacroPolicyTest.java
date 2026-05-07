package baritone.pathing.macro.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class MacroPolicyTest {
  @Test
  public void policyScoresVectorsWithoutChangingStateIdentity() {
    MacroCostVector cost = new MacroCostVector(100D, 4D, 20D, 0D, 0D, 0D, 0D);
    MacroAgentState state = MacroAgentState.pedestrian();
    MacroPolicy fast = new MacroPolicy(1D, 0D, 0D, 0D, 0D, 0D, 0D, 0);
    MacroPolicy hungry = new MacroPolicy(1D, 20D, 10D, 0D, 0D, 0D, 0D, 1);
    assertEquals(100D, fast.score(cost, state, state), 0D);
    assertEquals(380D, hungry.score(cost, state, state), 0D);
    assertEquals(state, MacroAgentState.pedestrian());
  }

  @Test
  public void boatPreferenceFallsOutOfFixedAndMarginalCosts() {
    MacroPolicy policy = new MacroPolicy(1D, 0D, 0D, 0D, 0D, 0D, 0D, 0);
    MacroAgentState pedestrian = MacroAgentState.pedestrian();

    double swimCostPerBlock = 3.564D;
    double boatCostPerBlock = 2.55D;
    double setup = 35D;
    double pickup = 35D;

    MacroCostVector shortBoat = MacroCostVector.fixedTime(setup).plus(MacroCostVector.boatTransit(20D, boatCostPerBlock)).plus(MacroCostVector.fixedTime(pickup));
    MacroCostVector longBoat = MacroCostVector.fixedTime(setup).plus(MacroCostVector.boatTransit(120D, boatCostPerBlock)).plus(MacroCostVector.fixedTime(pickup));

    assertTrue(policy.score(MacroCostVector.surfaceSwim(20D, swimCostPerBlock), pedestrian, pedestrian) < policy.score(shortBoat, pedestrian, pedestrian));
    assertTrue(policy.score(longBoat, pedestrian, pedestrian) < policy.score(MacroCostVector.surfaceSwim(120D, swimCostPerBlock), pedestrian, pedestrian));
    assertTrue(policy.score(MacroCostVector.fixedTime(setup).plus(MacroCostVector.boatTransit(120D, boatCostPerBlock)).plus(MacroCostVector.fixedTime(pickup)), pedestrian, pedestrian) < policy
      .score(MacroCostVector.fixedTime(setup).plus(MacroCostVector.boatTransit(60D, boatCostPerBlock)).plus(MacroCostVector.fixedTime(pickup)).plus(MacroCostVector.fixedTime(setup))
        .plus(MacroCostVector.boatTransit(60D, boatCostPerBlock)).plus(MacroCostVector.fixedTime(pickup)), pedestrian, pedestrian));
  }
}
