package baritone.pathing.route;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.water.WaterLineProfile;
import baritone.pathing.movement.water.WaterLineSegment;
import baritone.pathing.transport.TransportLeg;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportTerminality;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class RoutePlanTest {
  @Test
  public void legacyPathRemainsSoleLegacyPath() {
    IPath path = new FakePath(new BetterBlockPos(1, 64, 1), new BetterBlockPos(2, 64, 1));
    RoutePlan route = RoutePlan.legacy(path, PlannedTransportState.pedestrian(true));
    assertEquals(path, route.soleLegacyPath().orElseThrow());
    assertEquals(path.getSrc(), route.src());
    assertEquals(path.getDest(), route.dest());
  }

  @Test
  public void transitBoatLegDoesNotBecomeTerminal() {
    BetterBlockPos src = new BetterBlockPos(0, 63, 0);
    BetterBlockPos dest = new BetterBlockPos(8, 63, 0);
    WaterLineSegment segment = new WaterLineSegment(new TransportLeg(TransportMode.BOAT, src, src, dest, TransportTerminality.TRANSIT), dest,
      WaterLineProfile.overlayMountedBoat(new baritone.pathing.movement.water.WaterTransportPolicy(true, true, 40, 35, 35, 2.55, 0, 0), false), 8, 8 * 2.55, List.of(src, dest));
    SurfaceRouteLeg leg = new SurfaceRouteLeg(segment, PlannedTransportState.boat(true), PlannedTransportState.boat(true), BoatRecoveryPolicy.OPTIONAL, 0);
    assertFalse(leg.terminal());
    assertEquals(TransportMode.BOAT, leg.exitMode());
  }

  @Test
  public void swimSurfaceLegAcceptsDryAcquisitionDrift() {
    BetterBlockPos dry = new BetterBlockPos(10, 63, 10);
    BetterBlockPos waterStart = new BetterBlockPos(10, 62, 18);
    BetterBlockPos waterEnd = new BetterBlockPos(40, 62, 18);
    WaterLineSegment segment = new WaterLineSegment(new TransportLeg(TransportMode.SWIM, dry, waterStart, waterEnd, TransportTerminality.TRANSIT), waterEnd, WaterLineProfile.overlaySwim(3.5), 30, 105,
      List.of(dry, waterStart, waterEnd));
    SurfaceRouteLeg leg = new SurfaceRouteLeg(segment, PlannedTransportState.swim(false), PlannedTransportState.swim(false), BoatRecoveryPolicy.OPTIONAL, 0);
    assertTrue(leg.acceptsCorridor(new BetterBlockPos(10, 63, 11), false));
    assertFalse(leg.acceptsCorridor(new BetterBlockPos(10, 63, 14), false));
  }

  private record FakePath(BetterBlockPos src, BetterBlockPos dest) implements IPath {
    @Override
    public List<IMovement> movements() {
      return List.of();
    }

    @Override
    public List<BetterBlockPos> positions() {
      return List.of(src, dest);
    }

    @Override
    public Goal getGoal() { return new GoalBlock(dest); }

    @Override
    public int getNumNodesConsidered() { return 0; }
  }
}
