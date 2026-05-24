package baritone.pathing.farfield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FarfieldColumnTest {

  @Test
  public void columnFloorDivHandlesNegativeCoordinates() {
    assertEquals(new FarfieldColumnKey(null, -1, -1), FarfieldColumnKey.containing(null, -1, -1));
    assertEquals(new FarfieldColumnKey(null, 0, 0), FarfieldColumnKey.containing(null, 0, 15));
    assertEquals(new FarfieldColumnKey(null, 1, 1), FarfieldColumnKey.containing(null, 16, 16));
    assertEquals(new FarfieldColumnKey(null, -2, -2), FarfieldColumnKey.containing(null, -17, -17));
  }

  @Test
  public void profileNormalizesToOneByteSimplex() {
    FarfieldColumnProfile profile = FarfieldColumnProfile.mixed(300, 10, 10, 10, FarfieldEvidence.PRIOR, 64);
    assertEquals(255, profile.openSurface255() + profile.void255() + profile.lava255() + profile.solid255());
  }

  @Test
  public void costOrderingAndFloorInvariantHold() {
    FarfieldCosts costs = new FarfieldCosts(8F, 48F, 36F, 96F, 24F, 3.5F, 12F, 6F, 0.9F, 0.25F, 0.15F);
    var open = costs.cost(FarfieldColumnProfile.open(FarfieldEvidence.PRIOR, 64));
    var gap = costs.cost(FarfieldColumnProfile.voidGap(FarfieldEvidence.PRIOR, 64));
    var lava = costs.cost(FarfieldColumnProfile.lava(FarfieldEvidence.PRIOR, 64));
    var solid = costs.cost(FarfieldColumnProfile.solid(FarfieldEvidence.PRIOR, 64));
    assertTrue(open.expectedTicksPerBlock() < lava.expectedTicksPerBlock());
    assertTrue(lava.expectedTicksPerBlock() < gap.expectedTicksPerBlock());
    assertTrue(gap.expectedTicksPerBlock() < solid.expectedTicksPerBlock());
    for (var cost : new FarfieldCosts.FarfieldCost[]{open, gap, lava, solid}) {
      assertTrue(cost.floorTicksPerBlock() >= 0F);
      assertTrue(cost.floorTicksPerBlock() <= cost.expectedTicksPerBlock());
    }
  }
}
