package baritone.process.elytra;

import java.util.Objects;

record ElytraFireworkBoost(Integer fireworkTicksExisted, int minimumBoostTicks) {

  private int maximumBoostTicks() {
    return minimumBoostTicks + 11;
  }

  boolean isBoosted() { return fireworkTicksExisted != null; }

  int guaranteedBoostTicks() {
    return isBoosted() ? Math.max(0, minimumBoostTicks - fireworkTicksExisted) : 0;
  }

  int maximumRemainingBoostTicks() {
    return isBoosted() ? Math.max(0, maximumBoostTicks() - fireworkTicksExisted) : 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ElytraFireworkBoost other)) {
      return false;
    }
    if (!isBoosted() && !other.isBoosted()) {
      return true;
    }
    return minimumBoostTicks == other.minimumBoostTicks && maximumBoostTicks() == other.maximumBoostTicks() && Objects.equals(fireworkTicksExisted, other.fireworkTicksExisted);
  }

  @Override
  public int hashCode() {
    return isBoosted() ? Objects.hash(fireworkTicksExisted, minimumBoostTicks, maximumBoostTicks()) : 0;
  }
}
