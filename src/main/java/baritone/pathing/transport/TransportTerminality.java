package baritone.pathing.transport;

public enum TransportTerminality {
  TRANSIT, TERMINAL;

  public boolean terminal() {
    return this == TERMINAL;
  }
}
