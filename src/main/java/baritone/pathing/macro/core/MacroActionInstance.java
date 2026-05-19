package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.meso.MesoTaskIntent;
import java.util.List;

public record MacroActionInstance(MacroActionKind kind, long fromNode, long toNode, MacroAgentState before, MacroAgentState after, MacroCostVector cost, double score,
  List<BetterBlockPos> renderPositions, MacroSurfaceTransition surfaceTransition, MesoTaskIntent taskIntent) {
  public MacroActionInstance(MacroActionKind kind, long fromNode, long toNode, MacroAgentState before, MacroAgentState after, MacroCostVector cost, double score, List<BetterBlockPos> renderPositions,
    MacroSurfaceTransition surfaceTransition) {
    this(kind, fromNode, toNode, before, after, cost, score, renderPositions, surfaceTransition, null);
  }

  public MacroActionInstance {
    renderPositions = List.copyOf(renderPositions);
  }
}
