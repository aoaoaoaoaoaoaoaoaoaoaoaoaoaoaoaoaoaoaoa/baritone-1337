package baritone.pathing.transport;

public enum TransportMode {
  PEDESTRIAN, LEGACY_WATER, SWIM, BOAT, HORSE, ELYTRA;

  public boolean boat() {
    return this == BOAT;
  }

  public boolean inertial() {
    return this == BOAT || this == HORSE || this == ELYTRA || this == SWIM;
  }
}
