package baritone.api.utils;

public enum ElytraFireworkPolicy {
  RECOVERY(false, true, true), SPEED(true, true, true);

  private final boolean routineBoosts;
  private final boolean forcedBoosts;
  private final boolean fireworkReserveMatters;

  ElytraFireworkPolicy(boolean routineBoosts, boolean forcedBoosts, boolean fireworkReserveMatters) {
    this.routineBoosts = routineBoosts;
    this.forcedBoosts = forcedBoosts;
    this.fireworkReserveMatters = fireworkReserveMatters;
  }

  public boolean routineBoosts() {
    return routineBoosts;
  }

  public boolean forcedBoosts() {
    return forcedBoosts;
  }

  public boolean fireworkReserveMatters() {
    return fireworkReserveMatters;
  }

  public boolean energyGlide() {
    return !routineBoosts;
  }
}
