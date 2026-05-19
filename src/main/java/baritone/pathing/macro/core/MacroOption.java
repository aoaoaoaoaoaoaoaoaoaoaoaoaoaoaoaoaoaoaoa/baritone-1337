package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.meso.MesoTaskIntent;
import java.util.List;

public record MacroOption(MacroActionKind kind, long toNode, MacroAgentState nextState, MacroCostVector cost, List<BetterBlockPos> renderPositions, MacroSurfaceTransition surfaceTransition,
  MesoTaskIntent taskIntent) {
  public MacroOption(MacroActionKind kind, long toNode, MacroAgentState nextState, MacroCostVector cost, List<BetterBlockPos> renderPositions, MacroSurfaceTransition surfaceTransition) {
    this(kind, toNode, nextState, cost, renderPositions, surfaceTransition, null);
  }

  public MacroOption {
    renderPositions = List.copyOf(renderPositions);
  }
}
