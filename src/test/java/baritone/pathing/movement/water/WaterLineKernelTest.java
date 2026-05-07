package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import java.util.HashSet;
import org.junit.Test;

import static org.junit.Assert.*;

public class WaterLineKernelTest {
  @Test
  public void sweptTraceDistinguishesCenterlineFromHullCells() {
    HashSet<String> centerline = new HashSet<>();
    HashSet<String> hull = new HashSet<>();

    assertTrue(WaterLineKernel.traceCells(new BetterBlockPos(0, 63, 0), new BetterBlockPos(4, 63, 4), WaterLineProfile.BOAT_HALF_WIDTH, (x, z, onCenterline) -> {
      (onCenterline ? centerline : hull).add(x + "," + z);
      return true;
    }));

    assertTrue(centerline.contains("0,0"));
    assertTrue(centerline.contains("4,4"));
    assertTrue("diagonal boat sweeps cells outside its centerline", hull.stream().anyMatch(cell -> !centerline.contains(cell)));
  }
}
