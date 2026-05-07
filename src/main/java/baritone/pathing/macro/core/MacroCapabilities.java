package baritone.pathing.macro.core;

import baritone.pathing.movement.CalculationContext;

public record MacroCapabilities(boolean boatAvailable) {
  public static MacroCapabilities physical(CalculationContext context) {
    return new MacroCapabilities(context.waterTransport.boatAvailable() || context.waterTransport.boatMounted());
  }
}
