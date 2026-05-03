package baritone.api.process;

public enum ElytraLaunchMode {
  MANUAL(false, false, false), AUTO_JUMP(true, false, false), SPRINT_JUMP(false, true, true);

  private final boolean autoJump;
  private final boolean sprintJump;
  private final boolean launchFirework;

  ElytraLaunchMode(boolean autoJump, boolean sprintJump, boolean launchFirework) {
    this.autoJump = autoJump;
    this.sprintJump = sprintJump;
    this.launchFirework = launchFirework;
  }

  public boolean autoJump() {
    return autoJump;
  }

  public boolean sprintJump() {
    return sprintJump;
  }

  public boolean launchFirework() {
    return launchFirework;
  }
}
