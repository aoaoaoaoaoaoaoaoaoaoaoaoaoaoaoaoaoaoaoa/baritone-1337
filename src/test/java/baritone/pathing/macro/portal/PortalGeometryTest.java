package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroNodeKey;
import org.junit.Test;

import static org.junit.Assert.*;

public class PortalGeometryTest {
  @Test
  public void transformsOverworldAndNetherCoordinatesWithFloorSemantics() {
    BetterBlockPos overworld = new BetterBlockPos(404, 63, -7);
    BetterBlockPos nether = PortalGeometry.transform(overworld, MacroNodeKey.DIMENSION_OVERWORLD, MacroNodeKey.DIMENSION_NETHER);

    assertEquals(new BetterBlockPos(50, 63, -1), nether);
    assertEquals(new BetterBlockPos(400, 63, -8), PortalGeometry.transform(nether, MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD));
  }

  @Test
  public void portalSearchRadiusDependsOnDestinationDimension() {
    assertEquals(128, PortalGeometry.existingPortalSearchRadius(MacroNodeKey.DIMENSION_OVERWORLD));
    assertEquals(16, PortalGeometry.existingPortalSearchRadius(MacroNodeKey.DIMENSION_NETHER));
    assertTrue(PortalGeometry.wouldSnapToExistingPortal(new BetterBlockPos(0, 70, 0), new BetterBlockPos(127, 30, 0), MacroNodeKey.DIMENSION_OVERWORLD));
    assertFalse(PortalGeometry.wouldSnapToExistingPortal(new BetterBlockPos(0, 70, 0), new BetterBlockPos(17, 70, 0), MacroNodeKey.DIMENSION_NETHER));
  }

  @Test
  public void sourcePortalRelinkCheckUsesDestinationSearchSpace() {
    BetterBlockPos trapOverworldPortal = new BetterBlockPos(0, 64, 0);

    assertTrue(PortalGeometry.wouldSourcePortalRelink(new BetterBlockPos(16, 64, 0), MacroNodeKey.DIMENSION_NETHER, trapOverworldPortal));
    assertFalse(PortalGeometry.wouldSourcePortalRelink(new BetterBlockPos(17, 64, 0), MacroNodeKey.DIMENSION_NETHER, trapOverworldPortal));
  }

  @Test
  public void axisSeparationBeyondSearchRadiusScalesBetweenDimensions() {
    assertEquals(17, PortalGeometry.axisSeparationBeyondSearchRadius(MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD));
    assertEquals(129, PortalGeometry.axisSeparationBeyondSearchRadius(MacroNodeKey.DIMENSION_OVERWORLD, MacroNodeKey.DIMENSION_NETHER));
  }
}
