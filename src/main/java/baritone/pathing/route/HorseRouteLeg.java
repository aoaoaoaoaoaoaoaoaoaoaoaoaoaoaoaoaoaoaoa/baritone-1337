package baritone.pathing.route;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.mounted.HorsePath;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public record HorseRouteLeg(HorsePath path, PlannedTransportState entryState, PlannedTransportState exitState) implements RouteLeg {
  public HorseRouteLeg(HorsePath path, boolean boatAvailable) {
    this(path, PlannedTransportState.horse(boatAvailable), PlannedTransportState.horse(boatAvailable));
  }

  @Override
  public BetterBlockPos src() {
    return path.src();
  }

  @Override
  public BetterBlockPos dest() {
    return path.dest();
  }

  @Override
  public double estimatedTicks() {
    return path.estimatedTicks();
  }

  @Override
  public boolean contains(BlockPos feet) {
    long packed = feet.asLong();
    List<HorsePath.Waypoint> waypoints = path.waypoints();
    for (HorsePath.Waypoint waypoint : waypoints) {
      if (waypoint.pos().asLong() == packed) {
        return true;
      }
    }
    for (int i = 1; i < waypoints.size(); i++) {
      if (nearSegment(waypoints.get(i - 1).pos(), waypoints.get(i), feet)) {
        return true;
      }
    }
    return false;
  }

  public Optional<HorseRouteLeg> reanchor(BlockPos feet) {
    return reanchor(feet, 0);
  }

  public Optional<HorseRouteLeg> reanchor(BlockPos feet, int firstWaypointInclusive) {
    long packed = feet.asLong();
    List<HorsePath.Waypoint> waypoints = path.waypoints();
    int first = Math.max(0, Math.min(firstWaypointInclusive, waypoints.size() - 1));
    for (int i = first; i < waypoints.size() - 1; i++) {
      if (waypoints.get(i).pos().asLong() == packed) {
        return Optional.of(i == 0 ? this : new HorseRouteLeg(path.subPath(i, waypoints.size() - 1), entryState, exitState));
      }
    }
    return Optional.empty();
  }

  private static boolean nearSegment(BetterBlockPos a, HorsePath.Waypoint b, BlockPos p) {
    return nearSegmentAnchor(HorsePath.Waypoint.source(a), b, p) >= 0;
  }

  private static int nearSegmentAnchor(HorsePath.Waypoint a, HorsePath.Waypoint b, BlockPos p) {
    if (p.getY() < b.minYFromPrevious() - 2 || p.getY() > b.maxYFromPrevious() + 2) {
      return -1;
    }
    HorsePath.Track track = b.trackFromPrevious();
    double ax = track == null ? a.centerX() : track.startX();
    double az = track == null ? a.centerZ() : track.startZ();
    double bx = track == null ? b.centerX() : track.endX();
    double bz = track == null ? b.centerZ() : track.endZ();
    double px = p.getX() + 0.5D;
    double pz = p.getZ() + 0.5D;
    double vx = bx - ax;
    double vz = bz - az;
    double lengthSq = vx * vx + vz * vz;
    if (lengthSq < 1.0E-9D) {
      double dx = px - ax;
      double dz = pz - az;
      return dx * dx + dz * dz <= 4D ? 0 : -1;
    }
    double t = Math.max(0D, Math.min(1D, ((px - ax) * vx + (pz - az) * vz) / lengthSq));
    double dx = px - (ax + vx * t);
    double dz = pz - (az + vz * t);
    return dx * dx + dz * dz <= 4D ? t >= 0.45D ? 1 : 0 : -1;
  }

  @Override
  public List<BetterBlockPos> validPositions() {
    return path.positions();
  }

  @Override
  public List<BetterBlockPos> renderPositions() {
    return path.positions();
  }
}
