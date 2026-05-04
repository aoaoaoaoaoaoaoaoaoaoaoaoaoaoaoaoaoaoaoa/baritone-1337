package baritone.planning;

public record TimeoutPolicy(int ticks) {
  public static final TimeoutPolicy NONE = new TimeoutPolicy(0);
}
