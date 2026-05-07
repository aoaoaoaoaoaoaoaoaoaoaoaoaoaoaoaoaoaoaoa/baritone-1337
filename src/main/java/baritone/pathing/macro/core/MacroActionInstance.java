package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import java.util.List;

public record MacroActionInstance(MacroActionKind kind, long fromNode, long toNode, MacroAgentState before, MacroAgentState after, MacroCostVector cost, double score,
  List<BetterBlockPos> renderPositions, MacroSurfaceTransition surfaceTransition) {
  public MacroActionInstance {
    renderPositions = List.copyOf(renderPositions);
  }
}
