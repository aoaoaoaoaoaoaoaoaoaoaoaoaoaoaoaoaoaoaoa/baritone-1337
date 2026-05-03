package baritone.process.elytra;

record ElytraControlDecision(Mode mode, int phaseTicks, Float pitch, boolean firework, String fireworkReason, double y, double targetY, double floorY, double clearance, double debt,
  double horizontalSpeed, double verticalSpeed) {
  static ElytraControlDecision inactive(String reason, ElytraPath path, int near, double y, double horizontalSpeed, double verticalSpeed) {
    double targetY = path.isEmpty() ? y : path.get(Math.min(path.size() - 1, near + 3)).y;
    return new ElytraControlDecision(Mode.INACTIVE, 0, null, false, reason, y, targetY, targetY, y - targetY, targetY - y, horizontalSpeed, verticalSpeed);
  }

  ElytraControlDecision withFirework(String reason) {
    return new ElytraControlDecision(mode, phaseTicks, pitch, true, reason, y, targetY, floorY, clearance, debt, horizontalSpeed, verticalSpeed);
  }

  ElytraControlDecision recovering(float pitch, boolean firework, String reason) {
    return new ElytraControlDecision(Mode.RECOVERY, 1, pitch, firework, reason, y, targetY, floorY, clearance, debt, horizontalSpeed, verticalSpeed);
  }

  enum Mode {
    INACTIVE, DIVE, CLIMB, RECOVERY
  }
}
