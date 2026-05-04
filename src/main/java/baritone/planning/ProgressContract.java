package baritone.planning;

public record ProgressContract(ProgressMetric metric, StallPolicy stall, TimeoutPolicy timeout) {
  public static final ProgressContract NONE = new ProgressContract(new ProgressMetric.None(), StallPolicy.NONE, TimeoutPolicy.NONE);
}
