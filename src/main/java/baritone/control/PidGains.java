package baritone.control;

public record PidGains(double kp, double ki, double kd, double integralLimit, double outputLimit, double deadband) {
  public PidGains {
    requireFinite(kp);
    requireFinite(ki);
    requireFinite(kd);
    requireFinite(integralLimit);
    requireFinite(outputLimit);
    requireFinite(deadband);
    if (integralLimit < 0 || outputLimit < 0 || deadband < 0) {
      throw new IllegalArgumentException("pid limits must be nonnegative");
    }
  }

  private static void requireFinite(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("pid gains must be finite");
    }
  }
}
