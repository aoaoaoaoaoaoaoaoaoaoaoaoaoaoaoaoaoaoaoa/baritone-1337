package baritone.pathing.mounted;

import baritone.pathing.direct.DirectEdge;
import baritone.pathing.direct.DirectPathPuller;
import baritone.pathing.direct.DirectPullSchedule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

final class HorseDirectCruisePuller {
  private HorseDirectCruisePuller() {
  }

  static HorsePath pull(HorsePath raw, DirectPullSchedule schedule, Oracle oracle) {
    List<HorsePath.Waypoint> waypoints = raw.waypoints();
    if (waypoints.size() < 3) {
      return raw;
    }
    ArrayList<IndexedWaypoint> indexed = new ArrayList<>(waypoints.size());
    for (int i = 0; i < waypoints.size(); i++) {
      indexed.add(new IndexedWaypoint(i, waypoints.get(i)));
    }
    HashMap<Long, HorsePath.Cruise> certifiedSpans = new HashMap<>();
    DirectPathPuller.PullResult<IndexedWaypoint> pulled = DirectPathPuller.<IndexedWaypoint, Span>pull(indexed, schedule, HorseDirectCruisePuller::distance, (from, to) -> {
      if (!allLevelRawCruise(waypoints, from.index, to.index)) {
        return Optional.empty();
      }
      HorsePath.Cruise edge = oracle.certify(from.waypoint, to.waypoint);
      if (edge == null || edge.minY() != from.waypoint.pos().y || edge.maxY() != from.waypoint.pos().y) {
        return Optional.empty();
      }
      certifiedSpans.put(key(from.index, to.index), edge);
      return Optional.of(new Span(from, to, edge));
    });
    return pulled.changed() ? rebuild(waypoints, pulled.path(), certifiedSpans) : raw;
  }

  private static HorsePath rebuild(List<HorsePath.Waypoint> raw, List<IndexedWaypoint> pulled, HashMap<Long, HorsePath.Cruise> certifiedSpans) {
    ArrayList<HorsePath.Waypoint> out = new ArrayList<>(pulled.size());
    out.add(HorsePath.Waypoint.source(pulled.getFirst().waypoint.pos(), pulled.getFirst().waypoint.centerX(), pulled.getFirst().waypoint.centerZ()));
    double ticks = 0D;
    for (int i = 1; i < pulled.size(); i++) {
      IndexedWaypoint from = pulled.get(i - 1);
      IndexedWaypoint to = pulled.get(i);
      if (to.index == from.index + 1) {
        HorsePath.Waypoint waypoint = raw.get(to.index);
        out.add(waypoint);
        ticks += waypoint.costFromPrevious();
        continue;
      }
      HorsePath.Cruise edge = certifiedSpans.get(key(from.index, to.index));
      if (edge == null) {
        throw new IllegalStateException("missing certified direct horse cruise span " + from.index + " -> " + to.index);
      }
      HorsePath.Waypoint target = to.waypoint;
      out.add(new HorsePath.Waypoint(target.pos(), target.centerX(), target.centerZ(), edge));
      ticks += edge.costTicks();
    }
    return new HorsePath(out, ticks);
  }

  private static boolean allLevelRawCruise(List<HorsePath.Waypoint> waypoints, int from, int to) {
    if (to <= from + 1) {
      return false;
    }
    int y = waypoints.get(from).pos().y;
    for (int i = from + 1; i <= to; i++) {
      HorsePath.Waypoint waypoint = waypoints.get(i);
      if (waypoint.pos().y != y || !(waypoint.edgeFromPrevious() instanceof HorsePath.Cruise cruise) || cruise.minY() != y || cruise.maxY() != y) {
        return false;
      }
    }
    return true;
  }

  private static double distance(IndexedWaypoint a, IndexedWaypoint b) {
    return Math.hypot(b.waypoint.centerX() - a.waypoint.centerX(), b.waypoint.centerZ() - a.waypoint.centerZ());
  }

  private static long key(int from, int to) {
    return ((long) from << 32) ^ (to & 0xFFFF_FFFFL);
  }

  @FunctionalInterface
  interface Oracle {
    HorsePath.Cruise certify(HorsePath.Waypoint from, HorsePath.Waypoint to);
  }

  private record IndexedWaypoint(int index, HorsePath.Waypoint waypoint) {
  }

  private record Span(IndexedWaypoint from, IndexedWaypoint to, HorsePath.Cruise cruise) implements DirectEdge<IndexedWaypoint> {
    @Override
    public double cost() {
      return cruise.costTicks();
    }
  }
}
