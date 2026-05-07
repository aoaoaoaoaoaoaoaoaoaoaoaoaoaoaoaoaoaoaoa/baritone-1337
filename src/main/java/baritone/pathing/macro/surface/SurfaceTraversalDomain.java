package baritone.pathing.macro.surface;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.biome.BiomeSurfaceCost;
import baritone.pathing.macro.core.MacroActionKind;
import baritone.pathing.macro.core.MacroAgentState;
import baritone.pathing.macro.core.MacroCostVector;
import baritone.pathing.macro.core.MacroDomain;
import baritone.pathing.macro.core.MacroExpansionContext;
import baritone.pathing.macro.core.MacroLabel;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroOption;
import baritone.pathing.macro.core.MacroOptionSink;
import baritone.pathing.macro.core.MacroStratum;
import java.util.List;

public final class SurfaceTraversalDomain implements MacroDomain {
  private static final int[] D = {-1, 0, 1};

  @Override
  public String id() {
    return "surface";
  }

  @Override
  public void expand(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (MacroNodeKey.anchorKey(label.node()) || MacroNodeKey.stratum(label.node()) != MacroStratum.SURFACE || !label.state().pedestrianMode()) {
      return;
    }
    int x = MacroNodeKey.cellX(label.node());
    int z = MacroNodeKey.cellZ(label.node());
    for (int dx : D) {
      for (int dz : D) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        int nx = x + dx;
        int nz = z + dz;
        if (!context.inBounds(nx, nz)) {
          continue;
        }
        BetterBlockPos from = context.atlas().center(x, z);
        BetterBlockPos to = context.atlas().center(nx, nz);
        double distance = Math.hypot(to.x - from.x, to.z - from.z);
        MacroCostVector cost = cost(context, label.node(), nx, nz, distance);
        long next = MacroNodeKey.siblingCell(label.node(), nx, nz);
        MacroAgentState state = label.state();
        out.accept(new MacroOption(MacroActionKind.SURFACE_TRAVERSE, next, state, cost, List.of(from, to), null));
      }
    }
  }

  private static MacroCostVector cost(MacroExpansionContext context, long node, int nx, int nz, double distance) {
    if (MacroNodeKey.dimensionId(node) == MacroNodeKey.DIMENSION_NETHER) {
      return MacroCostVector.fixedTime(distance * Baritone.settings().macroNetherTicksPerBlock.value);
    }
    BiomeSurfaceCost prior = context.atlas().surfaceCost(nx, nz);
    return MacroCostVector.surfacePerBlock(prior).times(distance);
  }
}
