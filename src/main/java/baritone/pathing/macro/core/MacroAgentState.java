package baritone.pathing.macro.core;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.transport.TransportMode;

public record MacroAgentState(int bits) {
  private static final int MODE_MASK = 0b111;

  private static final int MODE_PEDESTRIAN = 0;
  private static final int MODE_BOAT = 1;
  private static final int MODE_SWIM = 2;

  public static MacroAgentState physical(CalculationContext context) {
    if (context.waterTransport.boatMounted()) {
      return boat();
    }
    return pedestrian();
  }

  public static MacroAgentState pedestrian() {
    return new MacroAgentState(MODE_PEDESTRIAN);
  }

  public static MacroAgentState boat() {
    return new MacroAgentState(MODE_BOAT);
  }

  public static MacroAgentState swim() {
    return new MacroAgentState(MODE_SWIM);
  }

  public TransportMode mode() {
    return switch (bits & MODE_MASK) {
      case MODE_BOAT -> TransportMode.BOAT;
      case MODE_SWIM -> TransportMode.SWIM;
      default -> TransportMode.PEDESTRIAN;
    };
  }

  public boolean pedestrianMode() {
    return (bits & MODE_MASK) == MODE_PEDESTRIAN;
  }

  public boolean boatMounted() {
    return (bits & MODE_MASK) == MODE_BOAT;
  }

  public boolean swimming() {
    return (bits & MODE_MASK) == MODE_SWIM;
  }

  public boolean surfaceWaterborne() {
    return boatMounted() || swimming();
  }

  public MacroAgentState afterSurfaceExit() {
    return pedestrian();
  }
}
