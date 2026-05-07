package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;
import java.util.Objects;

public record MacroSurfaceTransition(TransportMode mode, MacroSurfaceTransitionStage stage, BetterBlockPos dryStart, BetterBlockPos waterStart, BetterBlockPos waterEnd, BetterBlockPos dryEnd,
  int componentId, List<BetterBlockPos> waterPath, double distance) {
  public MacroSurfaceTransition {
    Objects.requireNonNull(mode);
    Objects.requireNonNull(stage);
    Objects.requireNonNull(waterStart);
    Objects.requireNonNull(waterEnd);
    if (mode != TransportMode.BOAT && mode != TransportMode.SWIM) {
      throw new IllegalArgumentException("surface transition mode must be boat or swim: " + mode);
    }
    switch (stage) {
      case ENTER -> {
        require(dryStart != null, "enter transition requires dryStart");
        require(dryEnd == null, "enter transition forbids dryEnd");
      }
      case TRANSIT -> {
        require(dryStart == null, "transit transition forbids dryStart");
        require(dryEnd == null, "transit transition forbids dryEnd");
      }
      case EXIT -> {
        require(dryStart == null, "exit transition forbids dryStart");
        require(dryEnd != null, "exit transition requires dryEnd");
      }
    }
    waterPath = List.copyOf(waterPath);
    if (waterPath.isEmpty()) {
      throw new IllegalArgumentException("surface transition water path must not be empty");
    }
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

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
