package baritone.pathing.calc;

import baritone.api.BaritoneAPI;

import baritone.api.Settings;
import baritone.pathing.transport.TransportMode;

public record PathingIncumbentPolicy(boolean earlyExecution, long intervalMS, int minLength, double heuristicMargin) {
  public PathingIncumbentPolicy {
    if (intervalMS < 0L) {
      throw new IllegalArgumentException("incumbent interval must be nonnegative: " + intervalMS);
    }
    if (minLength < 0) {
      throw new IllegalArgumentException("incumbent minimum length must be nonnegative: " + minLength);
    }
    if (!Double.isFinite(heuristicMargin) || heuristicMargin < 0D) {
      throw new IllegalArgumentException("incumbent heuristic margin must be nonnegative finite: " + heuristicMargin);
    }
  }

  public static PathingIncumbentPolicy forMode(TransportMode mode) {
    return switch (mode) {
      case HORSE -> horse();
      case PEDESTRIAN, LEGACY_WATER -> pedestrian();
      case SWIM, BOAT, ELYTRA -> defaults();
    };
  }

  public static PathingIncumbentPolicy defaults() {
    Settings s = BaritoneAPI.getSettings();
    return new PathingIncumbentPolicy(s.pathingEarlyIncumbentExecution.value, s.pathingIncumbentIntervalMS.value, s.pathingMinIncumbentLength.value, s.pathingIncumbentHeuristicMargin.value);
  }

  public static PathingIncumbentPolicy pedestrian() {
    Settings s = BaritoneAPI.getSettings();
    return new PathingIncumbentPolicy(s.pedestrianPathingEarlyIncumbentExecution.value, s.pedestrianPathingIncumbentIntervalMS.value, s.pedestrianPathingMinIncumbentLength.value,
      s.pedestrianPathingIncumbentHeuristicMargin.value);
  }

  public static PathingIncumbentPolicy horse() {
    Settings s = BaritoneAPI.getSettings();
    return new PathingIncumbentPolicy(s.horsePathingEarlyIncumbentExecution.value, s.horsePathingIncumbentIntervalMS.value, s.horsePathingMinIncumbentLength.value,
      s.horsePathingIncumbentHeuristicMargin.value);
  }

  public PathingIncumbentPolicy combinedWith(PathingIncumbentPolicy other) {
    return new PathingIncumbentPolicy(earlyExecution && other.earlyExecution, Math.min(intervalMS, other.intervalMS), Math.max(minLength, other.minLength),
      Math.max(heuristicMargin, other.heuristicMargin));
  }
}
