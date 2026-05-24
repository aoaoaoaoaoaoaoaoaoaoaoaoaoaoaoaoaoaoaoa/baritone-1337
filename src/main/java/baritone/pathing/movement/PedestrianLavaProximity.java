package baritone.pathing.movement;

import baritone.api.BaritoneAPI;

import static baritone.api.pathing.movement.ActionCosts.SPRINT_ONE_BLOCK_COST;
import static baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST;

public final class PedestrianLavaProximity {
  private PedestrianLavaProximity() {
  }

  public static double arrivalPenalty(CalculationContext context, int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
    if (BaritoneAPI.getSettings().assumeWalkOnLava.value) {
      return 0D;
    }
    return arrivalDanger(context, srcX, srcY, srcZ, destX, destY, destZ) ? analyticalPenalty(context) : 0D;
  }

  static double analyticalPenalty(CalculationContext context) {
    return analyticalPenalty(context.movement.canSprint());
  }

  static double analyticalPenalty(boolean sprint) {
    double flatStepCost = sprint ? SPRINT_ONE_BLOCK_COST : WALK_ONE_BLOCK_COST;
    return Math.nextUp(2D * flatStepCost);
  }

  public static boolean arrivalDanger(CalculationContext context, int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
    if (BaritoneAPI.getSettings().assumeWalkOnLava.value) {
      return false;
    }
    int dx = Integer.compare(destX, srcX);
    int dz = Integer.compare(destZ, srcZ);
    if (dx == 0 && dz == 0) {
      return false;
    }
    return dx != 0 && dz != 0 ? diagonalDanger(context, destX, destY, destZ, dx, dz) : orthogonalDanger(context, destX, destY, destZ, dx, dz);
  }

  private static boolean diagonalDanger(CalculationContext context, int x, int y, int z, int dx, int dz) {
    return lava(context, x + dx, y, z) || lava(context, x, y, z + dz);
  }

  private static boolean orthogonalDanger(CalculationContext context, int x, int y, int z, int dx, int dz) {
    if (dx != 0) {
      return lava(context, x + dx, y, z) || lava(context, x, y, z - 1) || lava(context, x, y, z + 1);
    }
    return lava(context, x, y, z + dz) || lava(context, x - 1, y, z) || lava(context, x + 1, y, z);
  }

  private static boolean lava(CalculationContext context, int x, int y, int z) {
    return context.affordances.lava(x, y, z);
  }
}
