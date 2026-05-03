package baritone.control;

public final class PidController {
  private final PidGains gains;
  private boolean hasPreviousError;
  private double previousError;
  private double integral;

  public PidController(PidGains gains) {
    this.gains = gains;
  }

  public double update(double error) {
    if (Math.abs(error) <= gains.deadband()) {
      error = 0;
    }
    integral = clamp(integral + error, gains.integralLimit());
    double derivative = hasPreviousError ? error - previousError : 0;
    previousError = error;
    hasPreviousError = true;
    return clamp(gains.kp() * error + gains.ki() * integral + gains.kd() * derivative, gains.outputLimit());
  }

  public void reset() {
    hasPreviousError = false;
    previousError = 0;
    integral = 0;
  }

  public void copyFrom(PidController source) {
    this.hasPreviousError = source.hasPreviousError;
    this.previousError = source.previousError;
    this.integral = source.integral;
  }

  public PidController fork() {
    PidController copy = new PidController(gains);
    copy.copyFrom(this);
    return copy;
  }

  private static double clamp(double value, double magnitude) {
    return Math.max(-magnitude, Math.min(magnitude, value));
  }
}
