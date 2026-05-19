package baritone.pathing.macro.core;

import baritone.pathing.transport.TransportMode;
import org.junit.Test;

import static org.junit.Assert.*;

public class MacroAgentStateTest {
  @Test
  public void onlyPhysicalTransportModeIsEncoded() {
    MacroAgentState pedestrian = MacroAgentState.pedestrian();
    assertEquals(TransportMode.PEDESTRIAN, pedestrian.mode());
    assertFalse(pedestrian.boatMounted());

    MacroAgentState boat = MacroAgentState.boat();
    assertEquals(TransportMode.BOAT, boat.mode());
    assertTrue(boat.boatMounted());
    assertEquals(TransportMode.PEDESTRIAN, boat.afterSurfaceExit().mode());

    MacroAgentState swim = MacroAgentState.swim();
    assertEquals(TransportMode.SWIM, swim.mode());
    assertTrue(swim.swimming());
    assertTrue(swim.surfaceWaterborne());
    assertFalse(swim.boatMounted());

    MacroAgentState horse = MacroAgentState.horse();
    assertEquals(TransportMode.HORSE, horse.mode());
    assertTrue(horse.horseMounted());
    assertFalse(horse.surfaceWaterborne());
    assertEquals(MacroAgentState.pedestrian(), pedestrian);
  }
}
