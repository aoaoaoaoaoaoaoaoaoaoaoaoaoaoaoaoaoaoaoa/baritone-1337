package baritone.pathing.mounted;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.direct.DirectPullSchedule;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HorseDirectCruisePullerTest {
  @Test
  public void pullsAcrossRawCruiseRunsOnly() {
    HorsePath raw = cruiseLine(0, 6);
    HorsePath pulled = HorseDirectCruisePuller.pull(raw, DirectPullSchedule.descending(0D, 4D, 2D, 1D), spanCap(4));

    assertEquals(3, pulled.waypoints().size());
    assertEquals("CRUISE:4,0", pulled.waypoints().get(1).edgeDebugName());
    assertEquals("CRUISE:2,0", pulled.waypoints().get(2).edgeDebugName());
    assertEquals(6D, pulled.estimatedTicks(), 1e-12D);
  }

  @Test
  public void technicalEdgesAreHardBoundaries() {
    HorsePath raw = new HorsePath(List.of(source(0), cruise(1), template(2), cruise(3), cruise(4)), 4D);
    HorsePath pulled = HorseDirectCruisePuller.pull(raw, DirectPullSchedule.descending(0D, 4D, 2D, 1D), spanCap(4));

    assertEquals(List.of(0, 1, 2, 4), pulled.waypoints().stream().map(w -> w.pos().x).toList());
    assertEquals("TEMPLATE:CLIPPED_STEP", pulled.waypoints().get(2).edgeDebugName());
    assertEquals("CRUISE:2,0", pulled.waypoints().get(3).edgeDebugName());
  }

  @Test
  public void unchangedPathsPreserveIdentity() {
    HorsePath raw = cruiseLine(0, 2);

    assertSame(raw, HorseDirectCruisePuller.pull(raw, DirectPullSchedule.descending(0D, 4D, 2D, 1D), (from, to) -> null));
  }

  private static HorseDirectCruisePuller.Oracle spanCap(int maxDx) {
    return (from, to) -> Math.abs(to.pos().x - from.pos().x) <= maxDx && from.pos().z == to.pos().z
      ? new HorsePath.Cruise(new HorsePath.CruiseVector(to.pos().x - from.pos().x, to.pos().z - from.pos().z), Math.abs(to.pos().x - from.pos().x), from.pos().y, to.pos().y) : null;
  }

  private static HorsePath cruiseLine(int start, int end) {
    ArrayList<HorsePath.Waypoint> waypoints = new ArrayList<>();
    waypoints.add(source(start));
    for (int x = start + 1; x <= end; x++) {
      waypoints.add(cruise(x));
    }
    return new HorsePath(waypoints, end - start);
  }

  private static HorsePath.Waypoint source(int x) {
    return HorsePath.Waypoint.source(pos(x), x, 0D);
  }

  private static HorsePath.Waypoint cruise(int x) {
    return new HorsePath.Waypoint(pos(x), x, 0D, new HorsePath.Cruise(HorsePath.CruiseVector.XP, 1D, 64, 64));
  }

  private static HorsePath.Waypoint template(int x) {
    return new HorsePath.Waypoint(pos(x), x, 0D, new HorsePath.Template(HorsePath.TemplateKind.CLIPPED_STEP, new HorsePath.Track(x - 1D, 0D, x, 0D, false), 1D, 64, 64));
  }

  private static BetterBlockPos pos(int x) {
    return new BetterBlockPos(x, 64, 0);
  }
}
