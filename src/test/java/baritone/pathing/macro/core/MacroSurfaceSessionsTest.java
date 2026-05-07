package baritone.pathing.macro.core;

import static org.junit.Assert.*;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;
import org.junit.Test;

public class MacroSurfaceSessionsTest {
  @Test
  public void rejectsUsefulLookingWaterLoopsThatReturnToStart() {
    BetterBlockPos start = new BetterBlockPos(543, 63, 145);
    BetterBlockPos goal = new BetterBlockPos(499, 64, 179);
    List<MacroActionInstance> loop = List.of(
      action(MacroSurfaceTransition.enter(TransportMode.SWIM, new BetterBlockPos(568, 63, 136), new BetterBlockPos(567, 62, 137), 1)), action(MacroSurfaceTransition.transit(TransportMode.SWIM,
        new BetterBlockPos(567, 62, 137), new BetterBlockPos(544, 62, 144), 1, List.of(new BetterBlockPos(567, 62, 137), new BetterBlockPos(544, 62, 144)), 32D)),
      action(MacroSurfaceTransition.exit(TransportMode.SWIM, new BetterBlockPos(544, 62, 144), start, 1)));

    assertEquals(-1, MacroSurfaceSessions.firstUsefulEnter(loop, start, goal));
    assertEquals(-1, MacroSurfaceSessions.firstUsefulStart(loop, start, goal));
  }

  @Test
  public void acceptsSurfaceRunsThatActuallyAdvanceTowardGoal() {
    BetterBlockPos start = new BetterBlockPos(653, 63, 135);
    BetterBlockPos goal = new BetterBlockPos(403, 62, -7);
    List<MacroActionInstance> forward = List.of(action(MacroSurfaceTransition.enter(TransportMode.SWIM, new BetterBlockPos(592, 63, 159), new BetterBlockPos(591, 62, 160), 2)),
      action(MacroSurfaceTransition.transit(TransportMode.SWIM, new BetterBlockPos(591, 62, 160), new BetterBlockPos(567, 62, 137), 2,
        List.of(new BetterBlockPos(591, 62, 160), new BetterBlockPos(567, 62, 137)), 35D)),
      action(MacroSurfaceTransition.exit(TransportMode.SWIM, new BetterBlockPos(567, 62, 137), new BetterBlockPos(568, 63, 136), 2)));

    assertEquals(0, MacroSurfaceSessions.firstUsefulEnter(forward, start, goal));
    assertEquals(0, MacroSurfaceSessions.firstUsefulStart(forward, start, goal));
  }

  private static MacroActionInstance action(MacroSurfaceTransition transition) {
    return new MacroActionInstance(MacroActionKind.SURFACE_TRANSIT, 0L, 0L, MacroAgentState.pedestrian(), MacroAgentState.swim(), MacroCostVector.ZERO, 0D, transition.waterPath(), transition);
  }
}
