package baritone.pathing.meso;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroCapabilities;
import baritone.pathing.macro.core.MacroPolicy;
import baritone.pathing.movement.CalculationContext;

public record MesoTaskRequest<I extends MesoTaskIntent>(CalculationContext calculation, BetterBlockPos physicalStart, MacroCapabilities capabilities, MacroPolicy policy, I intent) {
  public MesoTaskRequest {
    if (calculation == null || physicalStart == null || capabilities == null || policy == null || intent == null) {
      throw new IllegalArgumentException("meso task request requires calculation, start, capabilities, policy, and intent");
    }
  }
}
