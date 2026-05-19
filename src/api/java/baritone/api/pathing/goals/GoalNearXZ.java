package baritone.api.pathing.goals;

import baritone.api.BaritoneAPI;
import baritone.api.utils.SettingsUtil;

public final class GoalNearXZ extends GoalXZ {
  private final int radius;
  private final int radiusSq;

  public GoalNearXZ(int x, int z, int radius) {
    super(x, z);
    if (radius < 0) {
      throw new IllegalArgumentException("XZ goal tolerance must be nonnegative: " + radius);
    }
    this.radius = radius;
    this.radiusSq = radius * radius;
  }

  @Override
  public boolean isInGoal(int x, int y, int z) {
    int dx = x - getX();
    int dz = z - getZ();
    return dx * dx + dz * dz <= radiusSq;
  }

  @Override
  public double heuristic(int x, int y, int z) {
    double dx = x - getX();
    double dz = z - getZ();
    double outsideBlocks = Math.max(0D, Math.hypot(dx, dz) - radius);
    return outsideBlocks * BaritoneAPI.getSettings().costHeuristic.value;
  }

  @Override
  public int xzRadius() {
    return radius;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || o instanceof GoalNearXZ goal && getX() == goal.getX() && getZ() == goal.getZ() && radius == goal.radius;
  }

  @Override
  public int hashCode() {
    int hash = 1791873246;
    hash = hash * 222601791 + getX();
    hash = hash * -1331679453 + getZ();
    hash = hash * 1103515245 + radius;
    return hash;
  }

  @Override
  public String toString() {
    return String.format("GoalNearXZ{x=%s,z=%s,radius=%d}", SettingsUtil.maybeCensor(getX()), SettingsUtil.maybeCensor(getZ()), radius);
  }
}
