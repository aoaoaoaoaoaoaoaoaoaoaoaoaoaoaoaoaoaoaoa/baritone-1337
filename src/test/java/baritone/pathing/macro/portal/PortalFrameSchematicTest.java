package baritone.pathing.macro.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

public class PortalFrameSchematicTest {
  @Test
  public void exposesMinimalXAxisFrameAndInteriorOnly() {
    PortalFrameSchematic schematic = new PortalFrameSchematic(Direction.Axis.X);

    assertEquals(4, schematic.widthX());
    assertEquals(5, schematic.heightY());
    assertEquals(1, schematic.lengthZ());
    assertEquals(PortalFrameSchematic.CellKind.FRAME, schematic.cellKind(1, 0, 0));
    assertEquals(PortalFrameSchematic.CellKind.FRAME, schematic.cellKind(0, 1, 0));
    assertEquals(PortalFrameSchematic.CellKind.INTERIOR, schematic.cellKind(1, 1, 0));
    assertEquals(PortalFrameSchematic.CellKind.OUTSIDE, schematic.cellKind(0, 0, 0));
    assertFalse(schematic.inSchematic(0, 0, 0, null));
    assertTrue(schematic.inSchematic(1, 1, 0, null));
  }

  @Test
  public void computesBuildOriginFromLowerLeftInterior() {
    BetterBlockPos lowerLeft = new BetterBlockPos(10, 64, -3);

    assertEquals(new BetterBlockPos(9, 63, -3), PortalFrameSchematic.originFor(lowerLeft, Direction.Axis.X));
    assertEquals(new BetterBlockPos(10, 63, -4), PortalFrameSchematic.originFor(lowerLeft, Direction.Axis.Z));
  }
}
