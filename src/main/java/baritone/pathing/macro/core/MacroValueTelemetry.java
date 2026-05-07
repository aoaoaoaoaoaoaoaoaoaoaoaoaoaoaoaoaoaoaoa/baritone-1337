package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;

public record MacroValueTelemetry(double expectedStartValue, double floorStartValue, int expectedRepairPops, int floorRepairPops, int expectedQueueSize, int floorQueueSize, BetterBlockPos target,
  String planner) {
  public static final MacroValueTelemetry EMPTY = new MacroValueTelemetry(Double.NaN, Double.NaN, 0, 0, 0, 0, null, "none");
}
