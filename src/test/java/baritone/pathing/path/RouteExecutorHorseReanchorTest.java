package baritone.pathing.path;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.mounted.HorsePath;
import baritone.pathing.route.HorseRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.RoutePlan;
import baritone.pathing.route.RouteProgress;
import baritone.pathing.route.RouteRenderPlan;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteExecutorHorseReanchorTest {
  @Test
  public void horseRouteIsNotExecutableFromMiddleOfSweptSegment() {
    RouteExecutor route = route(path(pos(0), pos(40)));

    assertTrue(route.containsPathPosition(new BlockPos(20, 64, 0)));
    assertTrue(route.reanchor(new BlockPos(0, 64, 0)).isPresent());
    assertFalse(route.reanchor(new BlockPos(20, 64, 0)).isPresent());
  }

  @Test
  public void mountedSuffixReplacementHonorsMinimumAnchorProgress() {
    RouteExecutor current = route(path(pos(0), pos(20)));
    RouteExecutor replacement = route(path(pos(20), pos(40)));

    assertTrue(current.tryReplaceSuffix(replacement, new RouteProgress(1, 1D)).isPresent());
    assertFalse(current.tryReplaceSuffix(replacement, new RouteProgress(25, 25D)).isPresent());
  }

  @Test
  public void currentHorseRouteRendersCommittedFutureSublegs() {
    RouteExecutor route = route(path(pos(0), pos(10)), path(pos(10), pos(20)), path(pos(20), pos(30)));

    RouteRenderPlan plan = route.renderPlan(true, new RouteProgress(2, 20D));

    assertEquals(RouteRenderPlan.SegmentRole.COMMITTED, plan.segments().get(0).role());
    assertEquals(RouteRenderPlan.SegmentRole.COMMITTED, plan.segments().get(1).role());
    assertEquals(RouteRenderPlan.SegmentRole.CANDIDATE, plan.segments().get(2).role());
  }

  @Test
  public void commitmentEndIsAnActualHorseRoutePoint() {
    RouteExecutor route = route(path(pos(0), pos(10), 10D), path(pos(10), pos(20), 100D), path(pos(20), pos(30), 10D));

    assertEquals(new RouteProgress(2, 110D), route.commitmentEnd(50D, 1));
    assertEquals(new RouteProgress(2, 110D), route.commitmentEnd(0D, 2));
  }

  @Test
  public void horseRouteProgressFindsExactLegBoundaries() {
    RouteExecutor route = route(path(pos(0), pos(10), 10D), path(pos(10), pos(20), 100D), path(pos(20), pos(30), 10D));

    assertEquals(new RouteProgress(0, 0D), route.progressOfExact(pos(0)).orElseThrow());
    assertEquals(new RouteProgress(1, 10D), route.progressOfExact(pos(10)).orElseThrow());
    assertEquals(new RouteProgress(2, 110D), route.progressOfExact(pos(20)).orElseThrow());
    assertTrue(route.progressOfExact(new BlockPos(15, 64, 0)).isEmpty());
  }

  @Test
  public void mountedRouteSuffixReplacementUsesExplicitLegBoundaryProgress() {
    RouteExecutor current = route(path(pos(0), pos(10), 10D), path(pos(10), pos(20), 10D), path(pos(20), pos(30), 10D));
    RouteExecutor replacement = route(path(pos(20), pos(40), 20D));

    assertTrue(current.tryReplaceSuffix(replacement, new RouteProgress(2, 20D)).isPresent());
    assertFalse(current.tryReplaceSuffix(replacement, new RouteProgress(3, 30D)).isPresent());
  }

  private static RouteExecutor route(HorsePath path) {
    return route(List.of(path));
  }

  private static RouteExecutor route(HorsePath... paths) {
    return route(List.of(paths));
  }

  private static RouteExecutor route(List<HorsePath> paths) {
    PlannedTransportState state = PlannedTransportState.horse(false);
    return new RouteExecutor(null, RoutePlan.of(paths.stream().<RouteLeg>map(path -> new HorseRouteLeg(path, state, state)).toList(), state, state), null);
  }

  private static HorsePath path(BetterBlockPos from, BetterBlockPos to) {
    double distance = Math.hypot(to.x - from.x, to.z - from.z);
    return path(from, to, distance);
  }

  private static HorsePath path(BetterBlockPos from, BetterBlockPos to, double cost) {
    return new HorsePath(List.of(HorsePath.Waypoint.source(from),
      new HorsePath.Waypoint(to, to.x, to.z, new HorsePath.Cruise(new HorsePath.CruiseVector(to.x - from.x, to.z - from.z), cost, Math.min(from.y, to.y), Math.max(from.y, to.y)))), cost);
  }

  private static BetterBlockPos pos(int x) {
    return new BetterBlockPos(x, 64, 0);
  }
}
