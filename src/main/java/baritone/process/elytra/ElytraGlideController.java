package baritone.process.elytra;

import net.minecraft.world.phys.Vec3;

final class ElytraGlideController {
  private static final float DIVE_PITCH = 32.5F;
  private static final float CLIMB_START_PITCH = -50F;
  private static final float CLIMB_PITCH_DELTA_PER_TICK = 0.5F;
  private static final double DIVE_ALTITUDE_BUDGET = 64;
  private static final double MIN_HORIZONTAL_SPEED_FOR_CLIMB = 1.55;
  private static final double FLOOR_MARGIN = 24;

  private Phase phase = Phase.DIVE;
  private float climbPitch = CLIMB_START_PITCH;
  private double diveStartY = Double.NaN;

  Float pitch(ElytraFlightPolicy policy, ElytraSolverContext context, boolean landingMode) {
    if (!policy.fireworkPolicy().energyGlide() || landingMode || context.boost.isBoosted()) {
      reset();
      return null;
    }
    double floorY = energyFloorY(policy, context);
    if (context.start.y < floorY + FLOOR_MARGIN) {
      reset();
      return null;
    }

    return switch (phase) {
      case DIVE -> dive(floorY, context);
      case CLIMB -> climb(context.start);
    };
  }

  private Float dive(double floorY, ElytraSolverContext context) {
    if (Double.isNaN(diveStartY)) {
      diveStartY = context.start.y;
    }
    if (diveStartY - context.start.y >= DIVE_ALTITUDE_BUDGET || context.start.y <= floorY + FLOOR_MARGIN || horizontalSpeed(context.motion) >= MIN_HORIZONTAL_SPEED_FOR_CLIMB) {
      phase = Phase.CLIMB;
      climbPitch = CLIMB_START_PITCH;
      return climb(context.start);
    }
    return DIVE_PITCH;
  }

  private Float climb(Vec3 start) {
    float pitch = climbPitch;
    climbPitch = Math.min(DIVE_PITCH, climbPitch + CLIMB_PITCH_DELTA_PER_TICK);
    if (pitch >= DIVE_PITCH) {
      phase = Phase.DIVE;
      diveStartY = start.y;
    }
    return pitch;
  }

  private void reset() {
    phase = Phase.DIVE;
    climbPitch = CLIMB_START_PITCH;
    diveStartY = Double.NaN;
  }

  private static double horizontalSpeed(Vec3 motion) {
    return Math.hypot(motion.x, motion.z);
  }

  private static double energyFloorY(ElytraFlightPolicy policy, ElytraSolverContext context) {
    int lookahead = Math.min(context.path.size() - 1, context.playerNear + 3);
    return Math.max(policy.cruiseFloorY(), context.path.get(lookahead).y - 48);
  }

  private enum Phase {
    DIVE, CLIMB
  }
}
