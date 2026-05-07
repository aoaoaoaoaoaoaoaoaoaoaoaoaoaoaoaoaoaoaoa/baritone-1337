package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;

public record MacroExpansionContext(CalculationContext calculation, MacroAtlas atlas, Goal goal, BetterBlockPos physicalStart, MacroPolicy policy, MacroCapabilities capabilities, int minCellX,
  int maxCellX, int minCellZ, int maxCellZ, int targetCellX, int targetCellZ, MacroPlanningMode planningMode) {
  public boolean inBounds(int cellX, int cellZ) {
    return cellX >= minCellX && cellX <= maxCellX && cellZ >= minCellZ && cellZ <= maxCellZ;
  }

  public boolean predictedWaterAllowed() {
    return planningMode.predictedWaterAllowed();
  }
}
