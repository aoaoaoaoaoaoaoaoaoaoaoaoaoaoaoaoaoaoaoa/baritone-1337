package baritone.process.elytra;

import net.minecraft.world.phys.Vec3;

final class ElytraGlideController {
  private static final float DIVE_PITCH = 32.5F;
  private static final float CLIMB_START_PITCH = -48F;
  private static final float RECOVERY_PITCH = -42F;
  private static final float CLIMB_PITCH_DELTA_PER_TICK = 0.5F;
  private static final int MIN_DIVE_TICKS = 12;
  private static final int MIN_CLIMB_TICKS = 24;
  private static final double DIVE_ALTITUDE_BUDGET = 64;
  private static final double CLIMB_TARGET_SPEED = 1.45;
  private static final double MIN_USEFUL_CLIMB_SPEED = 0.9;
  private static final double CLIMB_ABORT_SPEED = 0.9;
  private static final double STALL_SPEED = 0.35;
  private static final double LOW_ALTITUDE_BAND = 56;
  private static final double HIGH_ALTITUDE_BAND = 24;
  private static final double RECOVERY_CLEARANCE = 52;
  private static final double CRITICAL_CLEARANCE = 28;
  private static final double ENERGY_BANKRUPT_SPEED = 0.95;
  private static final double HARD_ALTITUDE_DEBT_EXTRA = 80;
  private static final double BAD_SINK_RATE = -0.28;

  private Phase phase = Phase.DIVE;
  private int phaseTicks;
  private float climbPitch = CLIMB_START_PITCH;
  private double diveStartY = Double.NaN;

  ElytraControlDecision decide(ElytraFlightPolicy policy, ElytraPath path, int playerNear, Vec3 start, Vec3 motion, ElytraFireworkBoost boost, boolean landingMode, double recoveryFloorY,
    boolean commit) {
    double speed = horizontalSpeed(motion);
    if (!policy.fireworkPolicy().energyGlide() || landingMode || boost.isBoosted()) {
      if (commit) reset();
      String reason = landingMode ? "landing" : boost.isBoosted() ? "boosted" : "policy";
      return ElytraControlDecision.inactive(reason, path, playerNear, start.y, speed, motion.y);
    }
    double targetY = energyTargetY(path, playerNear);
    double floorY = Double.isNaN(recoveryFloorY) ? targetY - policy.terrainClearance() : recoveryFloorY;
    double clearance = start.y - floorY;
    Step step = switch (phase) {
      case DIVE -> dive(clearance, start, speed);
      case CLIMB -> climb(targetY, start, motion, speed);
    };
    ElytraControlDecision decision =
      new ElytraControlDecision(step.phase().decisionMode(), step.phaseTicks(), step.pitch(), false, "none", start.y, targetY, floorY, clearance, targetY - start.y, speed, motion.y);
    String fireworkReason = fireworkReason(policy, decision);
    if (fireworkReason != null) {
      boolean firework = policy.fireworkPolicy().forcedBoosts();
      decision = decision.recovering(RECOVERY_PITCH, firework, fireworkReason);
      if (commit) {
        phase = Phase.CLIMB;
        phaseTicks = 1;
        climbPitch = Math.min(DIVE_PITCH, RECOVERY_PITCH + CLIMB_PITCH_DELTA_PER_TICK);
        diveStartY = Double.NaN;
      }
      return decision;
    }
    if (commit) {
      phase = step.phase();
      phaseTicks = step.phaseTicks();
      climbPitch = step.climbPitch();
      diveStartY = step.diveStartY();
    }
    return decision;
  }

  private static String fireworkReason(ElytraFlightPolicy policy, ElytraControlDecision decision) {
    boolean energyBankrupt = decision.horizontalSpeed() < ENERGY_BANKRUPT_SPEED && decision.verticalSpeed() <= 0.05;
    boolean hardAltitudeDebt = decision.debt() > policy.terrainClearance() + HARD_ALTITUDE_DEBT_EXTRA;
    boolean unrecoverablyLow = decision.clearance() < CRITICAL_CLEARANCE;
    boolean lowAndFallingFast = decision.clearance() < RECOVERY_CLEARANCE && decision.verticalSpeed() < BAD_SINK_RATE;
    boolean stalledBelowCruise = decision.debt() > LOW_ALTITUDE_BAND && decision.horizontalSpeed() < STALL_SPEED && decision.verticalSpeed() < 0;
    if (unrecoverablyLow) return "critical_clearance";
    if (lowAndFallingFast) return "low_falling_fast";
    if (stalledBelowCruise) return "stalled_below_cruise";
    if (hardAltitudeDebt && energyBankrupt) return "energy_bankrupt";
    return null;
  }

  private Step dive(double clearance, Vec3 start, double horizontalSpeed) {
    double diveStartY = Double.isNaN(this.diveStartY) ? start.y : this.diveStartY;
    int ticks = phaseTicks + 1;
    boolean altitudeBudgetSpent = diveStartY - start.y >= DIVE_ALTITUDE_BUDGET;
    boolean kineticBudgetFull = horizontalSpeed >= CLIMB_TARGET_SPEED;
    boolean altitudeBudgetSpentWithUsefulEnergy = altitudeBudgetSpent && horizontalSpeed >= MIN_USEFUL_CLIMB_SPEED;
    boolean floorApproaching = clearance < RECOVERY_CLEARANCE && horizontalSpeed >= MIN_USEFUL_CLIMB_SPEED;
    if (ticks >= MIN_DIVE_TICKS && (kineticBudgetFull || altitudeBudgetSpentWithUsefulEnergy || floorApproaching)) {
      return startClimb();
    }
    return new Step(Phase.DIVE, ticks, DIVE_PITCH, climbPitch, diveStartY);
  }

  private Step startClimb() {
    return new Step(Phase.CLIMB, 1, CLIMB_START_PITCH, Math.min(DIVE_PITCH, CLIMB_START_PITCH + CLIMB_PITCH_DELTA_PER_TICK), Double.NaN);
  }

  private Step climb(double targetY, Vec3 start, Vec3 motion, double horizontalSpeed) {
    int ticks = phaseTicks + 1;
    float pitch = climbPitch;
    float nextClimbPitch = Math.min(DIVE_PITCH, climbPitch + CLIMB_PITCH_DELTA_PER_TICK);
    boolean cycleComplete = pitch >= DIVE_PITCH;
    boolean crestSpent = ticks >= MIN_CLIMB_TICKS && pitch > -16 && motion.y < -0.04;
    boolean speedSpent = ticks >= MIN_CLIMB_TICKS && pitch > -38 && horizontalSpeed < CLIMB_ABORT_SPEED;
    boolean highEnough = ticks >= MIN_CLIMB_TICKS && start.y > targetY + HIGH_ALTITUDE_BAND && motion.y <= 0.05;
    if (cycleComplete || crestSpent || speedSpent || highEnough) {
      return startDive(start);
    }
    return new Step(Phase.CLIMB, ticks, pitch, nextClimbPitch, Double.NaN);
  }

  private Step startDive(Vec3 start) {
    return new Step(Phase.DIVE, 1, DIVE_PITCH, CLIMB_START_PITCH, start.y);
  }

  private void reset() {
    phase = Phase.DIVE;
    phaseTicks = 0;
    climbPitch = CLIMB_START_PITCH;
    diveStartY = Double.NaN;
  }

  private static double horizontalSpeed(Vec3 motion) {
    return Math.hypot(motion.x, motion.z);
  }

  private static double energyTargetY(ElytraPath path, int playerNear) {
    int lookahead = Math.min(path.size() - 1, playerNear + 3);
    return path.get(lookahead).y;
  }

  private record Step(Phase phase, int phaseTicks, float pitch, float climbPitch, double diveStartY) {
  }

  private enum Phase {
    DIVE, CLIMB;

    ElytraControlDecision.Mode decisionMode() {
      return this == DIVE ? ElytraControlDecision.Mode.DIVE : ElytraControlDecision.Mode.CLIMB;
    }
  }
}
