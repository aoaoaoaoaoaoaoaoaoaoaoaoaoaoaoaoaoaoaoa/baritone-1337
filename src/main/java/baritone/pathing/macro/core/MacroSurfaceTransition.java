package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;

public record MacroSurfaceTransition(TransportMode mode, MacroSurfaceTransitionStage stage, BetterBlockPos dryStart, BetterBlockPos waterStart, BetterBlockPos waterEnd, BetterBlockPos dryEnd,
  int componentId, List<BetterBlockPos> waterPath, double distance) {
  public MacroSurfaceTransition {
    if (mode != TransportMode.BOAT && mode != TransportMode.SWIM) {
      throw new IllegalArgumentException("surface transition mode must be boat or swim: " + mode);
    }
    waterPath = List.copyOf(waterPath);
    if (distance < 0D || !Double.isFinite(distance)) {
      throw new IllegalArgumentException("surface transition distance must be finite and nonnegative: " + distance);
    }
  }

  public static MacroSurfaceTransition enter(TransportMode mode, BetterBlockPos dry, BetterBlockPos water, int componentId) {
    return new MacroSurfaceTransition(mode, MacroSurfaceTransitionStage.ENTER, dry, water, water, null, componentId, List.of(water), 0D);
  }

  public static MacroSurfaceTransition transit(TransportMode mode, BetterBlockPos waterStart, BetterBlockPos waterEnd, int componentId, List<BetterBlockPos> waterPath, double distance) {
    return new MacroSurfaceTransition(mode, MacroSurfaceTransitionStage.TRANSIT, null, waterStart, waterEnd, null, componentId, waterPath, distance);
  }

  public static MacroSurfaceTransition exit(TransportMode mode, BetterBlockPos water, BetterBlockPos dry, int componentId) {
    return new MacroSurfaceTransition(mode, MacroSurfaceTransitionStage.EXIT, null, water, water, dry, componentId, List.of(water), 0D);
  }

  public boolean boat() {
    return mode == TransportMode.BOAT;
  }

  public boolean swim() {
    return mode == TransportMode.SWIM;
  }
}
