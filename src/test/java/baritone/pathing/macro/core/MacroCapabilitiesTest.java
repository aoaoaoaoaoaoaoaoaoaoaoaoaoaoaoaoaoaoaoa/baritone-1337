package baritone.pathing.macro.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class MacroCapabilitiesTest {
  @Test
  public void capabilitiesAreNotNodeState() {
    MacroCapabilities noBoat = new MacroCapabilities(false);
    MacroCapabilities boat = new MacroCapabilities(true);

    assertFalse(noBoat.boatAvailable());
    assertTrue(boat.boatAvailable());
    assertEquals(MacroAgentState.pedestrian(), MacroAgentState.pedestrian());
  }
}
