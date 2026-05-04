package baritone.pathing.route;

import baritone.pathing.movement.water.WaterLineSegment;

public record SurfaceLineLeg(int startIndex, int endExclusive, WaterLineSegment segment) {
  public boolean contains(int pathPosition) {
    return pathPosition >= startIndex && pathPosition < endExclusive;
  }
}
