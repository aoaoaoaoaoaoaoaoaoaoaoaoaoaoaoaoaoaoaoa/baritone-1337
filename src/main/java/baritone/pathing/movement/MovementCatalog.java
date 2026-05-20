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
    List<MovementPrimitive> primitives = new ArrayList<>(Moves.values().length + ObliqueMovementPrimitive.STRIDES.length);
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
    if (Baritone.settings().pedestrianCruiseRays.value && (context.world.dimension() != Level.NETHER || Baritone.settings().pedestrianCruiseRaysInNether.value)) {
      int max = Math.clamp(Baritone.settings().pedestrianCruiseRayMaxBlocks.value, 2, 32);
      addCruiseRays(primitives, max);
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

  private static void addCruiseRays(List<MovementPrimitive> primitives, int maxChebyshevBlocks) {
    for (int dx = -maxChebyshevBlocks; dx <= maxChebyshevBlocks; dx++) {
      for (int dz = -maxChebyshevBlocks; dz <= maxChebyshevBlocks; dz++) {
        if (MovementCruiseRay.validRay(dx, dz)) {
          primitives.add(new CruiseRayMovementPrimitive(dx, dz));
        }
      }
    }
  }
}
