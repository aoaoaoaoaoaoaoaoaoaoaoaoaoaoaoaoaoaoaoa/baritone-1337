package baritone.planning;

public record StallPolicy(int windowTicks, double minimumDelta) {
  public static final StallPolicy NONE = new StallPolicy(0, 0D);
}
