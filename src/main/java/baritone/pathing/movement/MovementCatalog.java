package baritone.pathing.movement;

import java.util.ArrayList;
import java.util.List;

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
}
