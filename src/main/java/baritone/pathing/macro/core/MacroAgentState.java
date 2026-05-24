package baritone.pathing.macro.core;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.transport.TransportMode;

public record MacroAgentState(int bits) {
  private static final int MODE_MASK = 0b111;

  private static final int MODE_PEDESTRIAN = 0;
  private static final int MODE_BOAT = 1;
  private static final int MODE_SWIM = 2;
  private static final int MODE_HORSE = 3;
  private static final MacroAgentState PEDESTRIAN = new MacroAgentState(MODE_PEDESTRIAN);
  private static final MacroAgentState BOAT = new MacroAgentState(MODE_BOAT);
  private static final MacroAgentState SWIM = new MacroAgentState(MODE_SWIM);
  private static final MacroAgentState HORSE = new MacroAgentState(MODE_HORSE);

  public MacroAgentState {
    if ((bits & ~MODE_MASK) != 0 || (bits & MODE_MASK) > MODE_HORSE) {
      throw new IllegalArgumentException("invalid macro agent-state bits: " + bits);
    }
  }

  public static MacroAgentState physical(CalculationContext context) {
    if (context.waterTransport.boatMounted()) {
      return boat();
    }
    return pedestrian();
  }

  public static MacroAgentState pedestrian() {
    return PEDESTRIAN;
  }

  public static MacroAgentState boat() {
    return BOAT;
  }

  public static MacroAgentState swim() {
    return SWIM;
  }

  public static MacroAgentState horse() {
    return HORSE;
  }

  public TransportMode mode() {
    return switch (bits & MODE_MASK) {
      case MODE_PEDESTRIAN -> TransportMode.PEDESTRIAN;
      case MODE_BOAT -> TransportMode.BOAT;
      case MODE_SWIM -> TransportMode.SWIM;
      case MODE_HORSE -> TransportMode.HORSE;
      default -> throw new IllegalStateException("invalid macro agent-state mode: " + bits);
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

  public boolean horseMounted() {
    return (bits & MODE_MASK) == MODE_HORSE;
  }

  public boolean surfaceWaterborne() {
    return boatMounted() || swimming();
  }

  public MacroAgentState afterSurfaceExit() {
    return pedestrian();
  }
}
