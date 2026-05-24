package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;

public interface MovementPrimitive {
  String debugName();

  DestinationSpec destinationSpec();

  void evaluate(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out);

  Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload);

  default void predecessorCandidates(int destX, int destY, int destZ, PredecessorSink out) {
    DestinationSpec spec = destinationSpec();
    BlockOffset d = spec.precheckOffset();
    if (!spec.dynamicXZ() && !spec.dynamicY()) {
      out.accept(destX - d.dx(), destY - d.dy(), destZ - d.dz());
      return;
    }
    int radiusX = Math.max(4, Math.abs(d.dx()) + 1);
    int radiusZ = Math.max(4, Math.abs(d.dz()) + 1);
    for (int x = destX - radiusX; x <= destX + radiusX; x++) {
      for (int z = destZ - radiusZ; z <= destZ + radiusZ; z++) {
        for (int y = destY - 1; y <= destY + 16; y++) {
          out.accept(x, y, z);
        }
      }
    }
  }

  double minimumCost(CalculationContext ctx);

  default boolean revalidatesDestinationDuringAssembly() {
    return false;
  }
}
