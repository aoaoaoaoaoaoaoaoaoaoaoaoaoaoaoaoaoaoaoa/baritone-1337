package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import java.util.List;

public record MacroOption(MacroActionKind kind, long toNode, MacroAgentState nextState, MacroCostVector cost, List<BetterBlockPos> renderPositions, MacroSurfaceTransition surfaceTransition) {
  public MacroOption {
    renderPositions = List.copyOf(renderPositions);
  }
}
