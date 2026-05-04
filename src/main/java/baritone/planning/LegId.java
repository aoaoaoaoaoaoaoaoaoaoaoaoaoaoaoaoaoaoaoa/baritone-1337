package baritone.planning;

public record LegId(long value) {
  public static final LegId NONE = new LegId(0L);
}
