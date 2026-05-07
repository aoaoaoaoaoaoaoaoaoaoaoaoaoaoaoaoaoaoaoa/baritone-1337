package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static org.junit.Assert.*;

public class MacroNodeKeyTest {
  @Test
  public void cellKeyRoundTripsSignedCoordinatesAndScale() {
    long key = MacroNodeKey.cell(0, MacroStratum.SURFACE, 2, -12345, 54321);
    assertFalse(MacroNodeKey.anchorKey(key));
    assertEquals(0, MacroNodeKey.dimensionId(key));
    assertEquals(MacroStratum.SURFACE, MacroNodeKey.stratum(key));
    assertEquals(2, MacroNodeKey.scale(key));
    assertEquals(-12345, MacroNodeKey.cellX(key));
    assertEquals(54321, MacroNodeKey.cellZ(key));
    assertEquals(64, MacroNodeKey.cellBlocks(key));
  }

  @Test
  public void cellContainingUsesScaleBlocks() {
    BetterBlockPos pos = new BetterBlockPos(-1, 70, 130);
    long key = MacroNodeKey.cell(0, MacroStratum.SURFACE, 1, Math.floorDiv(pos.x, 32), Math.floorDiv(pos.z, 32));
    assertEquals(-1, MacroNodeKey.cellX(key));
    assertEquals(4, MacroNodeKey.cellZ(key));
  }
}
