package baritone.pathing.movement;

import java.util.ArrayList;
import java.util.List;
import baritone.Baritone;
import baritone.pathing.movement.movements.MovementCruiseRay;
import net.minecraft.world.level.Level;

public final class MovementCatalog {
  private final MovementPrimitive[] primitives;

  private MovementCatalog(MovementPrimitive[] primitives) {
    this.primitives = primitives;
  }

  public static MovementCatalog legacyWalking(CalculationContext context) {
    int cruiseRayMax = Baritone.settings().pedestrianCruiseRays.value && (context.world.dimension() != Level.NETHER || Baritone.settings().pedestrianCruiseRaysInNether.value)
      ? Math.clamp(Baritone.settings().pedestrianCruiseRayMaxBlocks.value, 2, 32) : 0;
    List<MovementPrimitive> primitives =
      new ArrayList<>(Moves.values().length + (context.movement.allowObliqueWalk() ? ObliqueMovementPrimitive.STRIDES.length : 0) + cruiseRayCount(cruiseRayMax, context.movement.allowObliqueWalk()));
    for (Moves move : Moves.values()) {
      if (enabled(context, move)) {
        primitives.add(new LegacyMovesPrimitive(move));
      }
    }
    if (context.movement.allowObliqueWalk()) {
      for (int[] stride : ObliqueMovementPrimitive.STRIDES) {
        primitives.add(new ObliqueMovementPrimitive(stride[0], stride[1]));
      }
    }
    if (cruiseRayMax > 0) {
      addCruiseRays(primitives, cruiseRayMax, context.movement.allowObliqueWalk());
    }
    return new MovementCatalog(primitives.toArray(MovementPrimitive[]::new));
  }

  public MovementPrimitive[] primitives() {
    return primitives;
  }

  public MovementPrimitive primitive(short index) {
    return primitives[index];
  }

  public int size() {
    return primitives.length;
  }

  private static boolean enabled(CalculationContext context, Moves move) {
    return switch (move) {
      case DOWNWARD -> context.movement.allowDownward();
      case PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_EAST, PARKOUR_WEST -> context.movement.allowParkour();
      default -> true;
    };
  }

  private static void addCruiseRays(List<MovementPrimitive> primitives, int maxChebyshevBlocks, boolean allowOblique) {
    for (int dx = -maxChebyshevBlocks; dx <= maxChebyshevBlocks; dx++) {
      for (int dz = -maxChebyshevBlocks; dz <= maxChebyshevBlocks; dz++) {
        if (MovementCruiseRay.validRay(dx, dz) && (allowOblique || !oblique(dx, dz))) {
          primitives.add(new CruiseRayMovementPrimitive(dx, dz));
        }
      }
    }
  }

  private static boolean oblique(int dx, int dz) {
    int ax = Math.abs(dx);
    int az = Math.abs(dz);
    return ax != 0 && az != 0 && ax != az;
  }

  private static int cruiseRayCount(int maxChebyshevBlocks, boolean allowOblique) {
    if (maxChebyshevBlocks <= 0) {
      return 0;
    }
    return allowOblique ? (2 * maxChebyshevBlocks + 1) * (2 * maxChebyshevBlocks + 1) - 9 : 8 * (maxChebyshevBlocks - 1);
  }
}
