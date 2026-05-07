package baritone.pathing.macro.core;

import baritone.Baritone;

public enum MacroPlanningMode {
  PREDICTIVE(true, false), FACTUAL_SURFACE_PREFIX(false, true);

  private static final int MIN_WATER_MACRO_CELLS = 3;

  private final boolean predictedWaterAllowed;
  private final boolean surfaceTransitionForced;

  MacroPlanningMode(boolean predictedWaterAllowed, boolean surfaceTransitionForced) {
    this.predictedWaterAllowed = predictedWaterAllowed;
    this.surfaceTransitionForced = surfaceTransitionForced;
  }

  boolean predictedWaterAllowed() {
    return predictedWaterAllowed;
  }

  double minimumDistance(int cellBlocks, int waypointBlocks) {
    double waterMinimum = cellBlocks * (double) MIN_WATER_MACRO_CELLS;
    return surfaceTransitionForced ? waterMinimum : Math.max(waterMinimum, waypointBlocks);
  }

  boolean requiresSurfaceTransition(MacroAtlas atlas) {
    return surfaceTransitionForced || !atlas.empiricalPriors() || !Baritone.settings().macroBiome.value;
  }
}
