package baritone.control;

import baritone.api.utils.Rotation;

public final class RotationGovernor {
  private final AngularGovernor yaw;
  private final ScalarGovernor pitch;

  public RotationGovernor(ScalarGovernorSpec yawSpec, ScalarGovernorSpec pitchSpec) {
    this(new AngularGovernor(yawSpec), new ScalarGovernor(pitchSpec));
  }

  private RotationGovernor(AngularGovernor yaw, ScalarGovernor pitch) {
    this.yaw = yaw;
    this.pitch = pitch;
  }

  public Rotation govern(Rotation rawTarget) {
    return new Rotation((float) yaw.govern(rawTarget.getYaw()), Rotation.clampPitch((float) pitch.govern(rawTarget.getPitch())));
  }

  public void reset() {
    yaw.reset();
    pitch.reset();
  }

  public void reset(Rotation value) {
    yaw.reset(value.getYaw());
    pitch.reset(value.getPitch());
  }

  public void copyFrom(RotationGovernor source) {
    yaw.copyFrom(source.yaw);
    pitch.copyFrom(source.pitch);
  }

  public RotationGovernor fork() {
    return new RotationGovernor(yaw.fork(), pitch.fork());
  }
}
