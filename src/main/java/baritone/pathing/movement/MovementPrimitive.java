package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;

public interface MovementPrimitive {
  String debugName();

  DestinationSpec destinationSpec();

  void evaluate(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out);

  Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload);

  double minimumCost(CalculationContext ctx);

  default boolean revalidatesDestinationDuringAssembly() {
    return false;
  }
}
