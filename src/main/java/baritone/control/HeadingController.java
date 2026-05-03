package baritone.control;

import baritone.api.utils.Rotation;

public final class HeadingController {
  private final PidController pid;

  public HeadingController(PidGains gains) {
    this.pid = new PidController(gains);
  }

  private HeadingController(PidController pid) {
    this.pid = pid;
  }

  public float update(float currentYaw, float targetYaw) {
    return (float) pid.update(Rotation.normalizeYaw(targetYaw - currentYaw));
  }

  public void reset() {
    pid.reset();
  }

  public void copyFrom(HeadingController source) {
    pid.copyFrom(source.pid);
  }

  public HeadingController fork() {
    return new HeadingController(pid.fork());
  }
}
