package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.portal.PortalGeometry;
import baritone.pathing.movement.CalculationContext;

public record MacroExpansionContext(CalculationContext calculation, MacroAtlas atlas, Goal goal, BetterBlockPos physicalStart, MacroPolicy policy, MacroCapabilities capabilities,
  MacroTraversalProfile profile, int minCellX, int maxCellX, int minCellZ, int maxCellZ, int targetCellX, int targetCellZ, MacroPlanningMode planningMode) {
  public boolean inBounds(int cellX, int cellZ) {
    return cellX >= minCellX && cellX <= maxCellX && cellZ >= minCellZ && cellZ <= maxCellZ;
  }

  public boolean inBounds(long node, int cellX, int cellZ) {
    int dimensionId = MacroNodeKey.dimensionId(node);
    int projectedX = dimensionId == MacroNodeKey.DIMENSION_NETHER ? PortalGeometry.targetCellX(cellX, MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD) : cellX;
    int projectedZ = dimensionId == MacroNodeKey.DIMENSION_NETHER ? PortalGeometry.targetCellZ(cellZ, MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD) : cellZ;
    return inBounds(projectedX, projectedZ);
  }

  public boolean predictedWaterAllowed() {
    return planningMode.predictedWaterAllowed();
  }
}
