package baritone.pathing.macro.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class MacroCapabilitiesTest {
  @Test
  public void capabilitiesAreNotNodeState() {
    MacroCapabilities noBoat = new MacroCapabilities(false, 0, 0);
    MacroCapabilities boat = new MacroCapabilities(true, 0, 0);

    assertFalse(noBoat.boatAvailable());
    assertTrue(boat.boatAvailable());
    assertEquals(MacroAgentState.pedestrian(), MacroAgentState.pedestrian());
  }

  @Test
  public void portalResourcesRemainGlobalCapabilities() {
    MacroCapabilities oneKit = new MacroCapabilities(false, 10, 1);
    MacroCapabilities oneAndHalfFrames = new MacroCapabilities(false, 15, 2);
    MacroCapabilities twoKits = new MacroCapabilities(false, 20, 2);

    assertEquals(1, oneKit.netherKits());
    assertTrue(oneKit.canBuildPortal());
    assertFalse(oneKit.canBuildPortalPair());
    assertTrue(oneAndHalfFrames.canCompletePortalPair(5, 10));
    assertFalse(oneAndHalfFrames.canCompletePortalPair(6, 10));
    assertTrue(twoKits.canBuildPortalPair());
  }
}
