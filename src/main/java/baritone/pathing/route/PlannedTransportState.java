package baritone.pathing.route;

import baritone.pathing.transport.TransportMode;
import java.util.Objects;

public record PlannedTransportState(TransportMode mode, boolean boatAvailable, boolean boatMounted) {
  public PlannedTransportState {
    Objects.requireNonNull(mode);
    if (boatMounted != (mode == TransportMode.BOAT)) {
      throw new IllegalArgumentException("boatMounted must exactly match BOAT mode: mode=" + mode + ", boatMounted=" + boatMounted);
    }
    if (boatMounted && !boatAvailable) {
      throw new IllegalArgumentException("mounted boat state requires an available boat");
    }
  }

  public static PlannedTransportState pedestrian(boolean boatAvailable) {
    return new PlannedTransportState(TransportMode.PEDESTRIAN, boatAvailable, false);
  }

  public static PlannedTransportState swim(boolean boatAvailable) {
    return new PlannedTransportState(TransportMode.SWIM, boatAvailable, false);
  }

  public static PlannedTransportState boat(boolean boatAvailable) {
    return new PlannedTransportState(TransportMode.BOAT, boatAvailable, true);
  }
}
