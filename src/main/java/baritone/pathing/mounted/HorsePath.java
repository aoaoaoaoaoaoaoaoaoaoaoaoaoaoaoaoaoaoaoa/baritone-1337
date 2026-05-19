package baritone.pathing.mounted;

import baritone.api.utils.BetterBlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

public record HorsePath(List<Waypoint> waypoints, double estimatedTicks) {
  public HorsePath {
    waypoints = List.copyOf(waypoints);
    if (waypoints.size() < 2) {
      throw new IllegalArgumentException("horse path needs at least two waypoints");
    }
    if (!Double.isFinite(estimatedTicks) || estimatedTicks < 0D) {
      throw new IllegalArgumentException("horse path cost must be finite and nonnegative: " + estimatedTicks);
    }
    for (int i = 1; i < waypoints.size(); i++) {
      Waypoint from = waypoints.get(i - 1);
      Waypoint to = waypoints.get(i);
      Track track = to.trackFromPrevious();
      if (track != null && (!projectedTo(track.startX(), track.startZ(), from.pos()) || !projectedTo(track.endX(), track.endZ(), to.pos()))) {
        throw new IllegalArgumentException("horse edge track escapes certified projections: " + from.pos() + " -> " + to.pos() + " via " + track);
      }
    }
  }

  private static boolean projectedTo(double x, double z, BetterBlockPos pos) {
    return Math.floor(x) == pos.x && Math.floor(z) == pos.z;
  }

  public BetterBlockPos src() {
    return waypoints.getFirst().pos();
  }

  public BetterBlockPos dest() {
    return waypoints.getLast().pos();
  }

  public int edgeCount() {
    return waypoints.size() - 1;
  }

  public double flatDistance() {
    double distance = 0D;
    for (int i = 1; i < waypoints.size(); i++) {
      Waypoint a = waypoints.get(i - 1);
      Waypoint b = waypoints.get(i);
      distance += Math.hypot(b.centerX - a.centerX, b.centerZ - a.centerZ);
    }
    return distance;
  }

  public List<BetterBlockPos> positions() {
    return waypoints.stream().map(Waypoint::pos).toList();
  }

  public double estimatedTicksRemaining(int waypointIndex) {
    double ticks = 0D;
    for (int i = Math.max(1, waypointIndex + 1); i < waypoints.size(); i++) {
      ticks += waypoints.get(i).costFromPrevious();
    }
    return ticks;
  }

  public HorsePath subPath(int firstWaypointInclusive, int lastWaypointInclusive) {
    if (firstWaypointInclusive < 0 || lastWaypointInclusive >= waypoints.size() || firstWaypointInclusive >= lastWaypointInclusive) {
      throw new IllegalArgumentException("invalid horse path slice: " + firstWaypointInclusive + ".." + lastWaypointInclusive + " of " + waypoints.size());
    }
    ArrayList<Waypoint> slice = new ArrayList<>(lastWaypointInclusive - firstWaypointInclusive + 1);
    Waypoint src = waypoints.get(firstWaypointInclusive);
    slice.add(Waypoint.source(src.pos, src.centerX, src.centerZ));
    double ticks = 0D;
    for (int i = firstWaypointInclusive + 1; i <= lastWaypointInclusive; i++) {
      Waypoint waypoint = waypoints.get(i);
      slice.add(waypoint);
      ticks += waypoint.costFromPrevious();
    }
    return new HorsePath(slice, ticks);
  }

  public List<HorsePath> split(double maxLegTicks, int maxLegEdges) {
    if (!Double.isFinite(maxLegTicks) || maxLegTicks <= 0D || maxLegEdges <= 0) {
      throw new IllegalArgumentException("horse path split limits must be positive: ticks=" + maxLegTicks + ", edges=" + maxLegEdges);
    }
    ArrayList<HorsePath> legs = new ArrayList<>(Math.max(1, (edgeCount() + maxLegEdges - 1) / maxLegEdges));
    int start = 0;
    while (start < waypoints.size() - 1) {
      int end = start;
      double ticks = 0D;
      while (end + 1 < waypoints.size()) {
        double nextTicks = waypoints.get(end + 1).costFromPrevious();
        if (end > start && (end - start >= maxLegEdges || ticks + nextTicks > maxLegTicks)) {
          break;
        }
        end++;
        ticks += nextTicks;
      }
      legs.add(subPath(start, end));
      start = end;
    }
    return legs;
  }

  public static BetterBlockPos vehicleFeet(Entity vehicle) {
    return new BetterBlockPos(Mth.floor(vehicle.getX()), Mth.floor(vehicle.getBoundingBox().minY + 0.01D), Mth.floor(vehicle.getZ()));
  }

  public record Waypoint(BetterBlockPos pos, double centerX, double centerZ, Edge edgeFromPrevious) {
    public static Waypoint source(BetterBlockPos pos) {
      return source(pos, pos.x, pos.z);
    }

    public static Waypoint source(BetterBlockPos pos, double centerX, double centerZ) {
      return new Waypoint(pos, centerX, centerZ, new Source(pos.y));
    }

    public static Waypoint cruise(BetterBlockPos pos, double x, double z, CruiseVector vector, double costTicks, int minY, int maxY) {
      return new Waypoint(pos, x, z, new Cruise(vector, costTicks, minY, maxY));
    }

    public Waypoint {
      if (pos == null) {
        throw new IllegalArgumentException("horse waypoint position must not be null");
      }
      if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
        throw new IllegalArgumentException("horse waypoint center must be finite: " + centerX + "," + centerZ);
      }
      if (!projectedTo(centerX, centerZ, pos)) {
        throw new IllegalArgumentException("horse waypoint coordinate " + centerX + "," + centerZ + " projects outside " + pos);
      }
      if (edgeFromPrevious == null) {
        throw new IllegalArgumentException("horse edge must not be null");
      }
    }

    public double costFromPrevious() {
      return edgeFromPrevious.costTicks();
    }

    public int minYFromPrevious() {
      return edgeFromPrevious.minY();
    }

    public int maxYFromPrevious() {
      return edgeFromPrevious.maxY();
    }

    public Track trackFromPrevious() {
      return edgeFromPrevious.track();
    }

    public boolean cruiseFromPrevious() {
      return edgeFromPrevious instanceof Cruise;
    }

    public boolean templateFromPrevious() {
      return edgeFromPrevious instanceof Template;
    }

    public String edgeDebugName() {
      return edgeFromPrevious.debugName();
    }

    public String edgeDebugSignature() {
      StringBuilder out = new StringBuilder(edgeFromPrevious.debugName()).append(" y=").append(edgeFromPrevious.minY()).append("..").append(edgeFromPrevious.maxY()).append(" cost=")
        .append(String.format(Locale.ROOT, "%.2f", edgeFromPrevious.costTicks())).append(" center=").append(String.format(Locale.ROOT, "%.3f,%.3f", centerX, centerZ));
      Track track = edgeFromPrevious.track();
      if (track != null) {
        out.append(" track=").append(String.format(Locale.ROOT, "%.3f,%.3f->%.3f,%.3f", track.startX(), track.startZ(), track.endX(), track.endZ()));
        if (track.staged()) {
          out.append(":staged");
        }
        if (track.recenter()) {
          out.append(":recenter");
        }
      }
      return out.toString();
    }
  }

  public record Track(double startX, double startZ, double endX, double endZ, boolean staged, boolean recenter) {
    public Track(double startX, double startZ, double endX, double endZ, boolean staged) {
      this(startX, startZ, endX, endZ, staged, false);
    }

    public Track {
      if (!Double.isFinite(startX) || !Double.isFinite(startZ) || !Double.isFinite(endX) || !Double.isFinite(endZ)) {
        throw new IllegalArgumentException("horse track coordinates must be finite");
      }
    }
  }

  public sealed interface Edge permits Source, Stationary, Cruise, Template, WaterDrop {
    EdgeRegime regime();

    double costTicks();

    int minY();

    int maxY();

    default Track track() {
      return null;
    }

    default String debugName() {
      return regime().name();
    }

    private static void requireEnvelope(double costTicks, int minY, int maxY) {
      if (!Double.isFinite(costTicks) || costTicks < 0D) {
        throw new IllegalArgumentException("edge cost must be finite and nonnegative: " + costTicks);
      }
      if (minY > maxY) {
        throw new IllegalArgumentException("horse edge y envelope is inverted: " + minY + " > " + maxY);
      }
    }
  }

  public record Source(int y) implements Edge {
    @Override
    public EdgeRegime regime() {
      return EdgeRegime.SOURCE;
    }

    @Override
    public double costTicks() {
      return 0D;
    }

    @Override
    public int minY() {
      return y;
    }

    @Override
    public int maxY() {
      return y;
    }
  }

  public record Stationary(double costTicks, int y) implements Edge {
    public Stationary {
      Edge.requireEnvelope(costTicks, y, y);
    }

    @Override
    public EdgeRegime regime() {
      return EdgeRegime.STATIONARY;
    }

    @Override
    public int minY() {
      return y;
    }

    @Override
    public int maxY() {
      return y;
    }
  }

  public record Cruise(CruiseVector vector, double costTicks, int minY, int maxY) implements Edge {
    public Cruise {
      if (vector == null) {
        throw new IllegalArgumentException("horse cruise vector must not be null");
      }
      Edge.requireEnvelope(costTicks, minY, maxY);
    }

    @Override
    public EdgeRegime regime() {
      return EdgeRegime.CRUISE;
    }

    @Override
    public String debugName() {
      return "CRUISE:" + vector.debugName();
    }
  }

  public record Template(TemplateKind kind, Track track, double costTicks, int minY, int maxY) implements Edge {
    public Template {
      if (kind == null) {
        throw new IllegalArgumentException("horse template kind must not be null");
      }
      if (track == null) {
        throw new IllegalArgumentException("horse template must carry its actuator track");
      }
      Edge.requireEnvelope(costTicks, minY, maxY);
    }

    @Override
    public EdgeRegime regime() {
      return EdgeRegime.TEMPLATE;
    }

    @Override
    public String debugName() {
      return "TEMPLATE:" + kind.name();
    }
  }

  public record WaterDrop(double costTicks, int minY, int maxY) implements Edge {
    public WaterDrop {
      Edge.requireEnvelope(costTicks, minY, maxY);
    }

    @Override
    public EdgeRegime regime() {
      return EdgeRegime.WATER_DROP;
    }
  }

  public enum EdgeRegime {
    SOURCE, STATIONARY, CRUISE, TEMPLATE, WATER_DROP
  }

  public enum TemplateKind {
    PHYSICAL_ROOT_STEP, CLIPPED_STEP
  }

  public record CruiseVector(int dx, int dz) {
    public static final CruiseVector XP = new CruiseVector(1, 0);
    public static final CruiseVector XN = new CruiseVector(-1, 0);
    public static final CruiseVector ZP = new CruiseVector(0, 1);
    public static final CruiseVector ZN = new CruiseVector(0, -1);
    public static final CruiseVector XP_ZP = new CruiseVector(1, 1);
    public static final CruiseVector XP_ZN = new CruiseVector(1, -1);
    public static final CruiseVector XN_ZP = new CruiseVector(-1, 1);
    public static final CruiseVector XN_ZN = new CruiseVector(-1, -1);
    public static final CruiseVector[] CRUISE8 = {XP, XN, ZP, ZN, XP_ZP, XP_ZN, XN_ZP, XN_ZN};

    public CruiseVector {
      if (dx == 0 && dz == 0) {
        throw new IllegalArgumentException("horse cruise vector must move");
      }
    }

    public boolean adjacent8() {
      return Math.max(Math.abs(dx), Math.abs(dz)) == 1;
    }

    public String debugName() {
      return dx + "," + dz;
    }
  }
}
