package baritone.planning;

public record BoatLease(int entityId) {
  public static final BoatLease UNCONFIRMED = new BoatLease(-1);

  public boolean confirmed() {
    return entityId >= 0;
  }
}
