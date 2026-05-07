package baritone.pathing.route;

import baritone.pathing.transport.TransportMode;

public record PlannedTransportState(TransportMode mode, boolean boatAvailable, boolean boatMounted) {
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
