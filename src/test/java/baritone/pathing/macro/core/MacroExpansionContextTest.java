package baritone.pathing.macro.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MacroExpansionContextTest {
  @Test
  public void netherCellsAreClippedInOverworldProjectedCoordinates() {
    MacroExpansionContext context = new MacroExpansionContext(null, null, null, null, null, null, MacroTraversalProfile.pedestrian(), 960, 1_040, -40, 40, 1_000, 0, MacroPlanningMode.PREDICTIVE);
    long nether = MacroNodeKey.cell(MacroNodeKey.DIMENSION_NETHER, MacroStratum.SURFACE, 0, 125, 0);
    long overworld = MacroNodeKey.cell(MacroNodeKey.DIMENSION_OVERWORLD, MacroStratum.SURFACE, 0, 125, 0);

    assertTrue(context.inBounds(nether, 125, 0));
    assertFalse(context.inBounds(overworld, 125, 0));
  }
}
