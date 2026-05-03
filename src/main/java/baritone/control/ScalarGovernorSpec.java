package baritone.control;

public record ScalarGovernorSpec(double targetDeadband, double targetResponse, double targetMaxStep, double outputDeadband, double outputResponse, double outputMaxStep) {
  public static final ScalarGovernorSpec IDENTITY = new ScalarGovernorSpec(0, 1, Double.MAX_VALUE, 0, 1, Double.MAX_VALUE);

  public ScalarGovernorSpec {
    requireFinite(targetDeadband);
    requireFinite(targetResponse);
    requireFinite(targetMaxStep);
    requireFinite(outputDeadband);
    requireFinite(outputResponse);
    requireFinite(outputMaxStep);
    if (targetDeadband < 0 || targetResponse < 0 || targetMaxStep < 0 || outputDeadband < 0 || outputResponse < 0 || outputMaxStep < 0) {
      throw new IllegalArgumentException("governor parameters must be nonnegative");
    }
  }

  private static void requireFinite(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("governor parameters must be finite");
    }
  }
}
