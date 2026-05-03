package baritone.control;

public class ScalarGovernor {
  protected final ScalarGovernorSpec spec;
  protected boolean hasTarget;
  protected double target;
  protected double output;

  public ScalarGovernor(ScalarGovernorSpec spec) {
    this.spec = spec;
  }

  public double govern(double rawTarget) {
    rawTarget = normalize(rawTarget);
    if (!hasTarget) {
      reset(rawTarget);
      return output;
    }
    double targetDelta = delta(rawTarget, target);
    if (Math.abs(targetDelta) > spec.targetDeadband()) {
      target = normalize(target + clamp(targetDelta * spec.targetResponse(), spec.targetMaxStep()));
    }
    double outputDelta = delta(target, output);
    if (Math.abs(outputDelta) > spec.outputDeadband()) {
      output = normalize(output + clamp(outputDelta * spec.outputResponse(), spec.outputMaxStep()));
    }
    return output;
  }

  public void reset() {
    hasTarget = false;
    target = 0;
    output = 0;
  }

  public void reset(double value) {
    hasTarget = true;
    target = normalize(value);
    output = target;
  }

  public void copyFrom(ScalarGovernor source) {
    this.hasTarget = source.hasTarget;
    this.target = source.target;
    this.output = source.output;
  }

  public ScalarGovernor fork() {
    ScalarGovernor copy = new ScalarGovernor(spec);
    copy.copyFrom(this);
    return copy;
  }

  public double output() {
    return output;
  }

  protected double delta(double desired, double current) {
    return desired - current;
  }

  protected double normalize(double value) {
    return value;
  }

  protected static double clamp(double value, double magnitude) {
    return Math.max(-magnitude, Math.min(magnitude, value));
  }
}
