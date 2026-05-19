package baritone.pathing.route;

public record RouteProgress(int units, double ticks) {
  private static final double EPSILON_TICKS = 1.0E-6D;

  public RouteProgress {
    if (units < 0) {
      throw new IllegalArgumentException("route progress units must be nonnegative: " + units);
    }
    if (!Double.isFinite(ticks) || ticks < 0D) {
      throw new IllegalArgumentException("route progress ticks must be finite and nonnegative: " + ticks);
    }
  }

  public static RouteProgress origin() {
    return new RouteProgress(0, 0D);
  }

  public RouteProgress plus(int extraUnits, double extraTicks) {
    if (extraUnits < 0 || !Double.isFinite(extraTicks) || extraTicks < 0D) {
      throw new IllegalArgumentException("route progress increments must be nonnegative: units=" + extraUnits + ", ticks=" + extraTicks);
    }
    return new RouteProgress(units + extraUnits, ticks + extraTicks);
  }

  public boolean reaches(RouteProgress threshold) {
    return units >= threshold.units && ticks + EPSILON_TICKS >= threshold.ticks;
  }
}
