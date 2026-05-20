package baritone.pathing.movement;

import baritone.Baritone;

public final class PedestrianLavaProximity {
  private PedestrianLavaProximity() {
  }

  public static double arrivalPenalty(CalculationContext context, int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
    double penalty = context.costs.pedestrianLavaProximityPenalty();
    if (penalty <= 0D || Baritone.settings().assumeWalkOnLava.value) {
      return 0D;
    }
    int dx = Integer.compare(destX, srcX);
    int dz = Integer.compare(destZ, srcZ);
    if (dx == 0 && dz == 0) {
      return 0D;
    }
    return (dx != 0 && dz != 0 ? diagonalDanger(context, destX, destY, destZ, dx, dz) : orthogonalDanger(context, destX, destY, destZ, dx, dz)) ? penalty : 0D;
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
