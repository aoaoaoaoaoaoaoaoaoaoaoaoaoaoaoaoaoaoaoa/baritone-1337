package baritone.control;

import baritone.api.utils.Rotation;

public final class AngularGovernor extends ScalarGovernor {
  public AngularGovernor(ScalarGovernorSpec spec) {
    super(spec);
  }

  @Override
  public AngularGovernor fork() {
    AngularGovernor copy = new AngularGovernor(spec);
    copy.copyFrom(this);
    return copy;
  }

  @Override
  protected double delta(double desired, double current) {
    return Rotation.normalizeYaw((float) (desired - current));
  }

  @Override
  protected double normalize(double value) {
    return Rotation.normalizeYaw((float) value);
  }
}
