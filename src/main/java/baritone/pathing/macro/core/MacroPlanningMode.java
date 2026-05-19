package baritone.pathing.macro.core;

public enum MacroPlanningMode {
  PREDICTIVE(true, false), FACTUAL_SURFACE_PREFIX(false, true);

  private static final int MIN_WATER_MACRO_CELLS = 3;

  private final boolean predictedWaterAllowed;
  private final boolean transportTransitForced;

  MacroPlanningMode(boolean predictedWaterAllowed, boolean transportTransitForced) {
    this.predictedWaterAllowed = predictedWaterAllowed;
    this.transportTransitForced = transportTransitForced;
  }

  boolean predictedWaterAllowed() {
    return predictedWaterAllowed;
  }

  double minimumDistance(int cellBlocks, int waypointBlocks) {
    double waterMinimum = cellBlocks * (double) MIN_WATER_MACRO_CELLS;
    return transportTransitForced ? waterMinimum : Math.max(waterMinimum, waypointBlocks);
  }

}
