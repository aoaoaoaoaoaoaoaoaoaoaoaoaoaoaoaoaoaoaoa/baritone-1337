package baritone.pathing.macro.portal;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
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

public final class PortalTransportDomain implements MacroDomain {
  private static final double DEFAULT_IGNITION_TICKS = 60D;
  private final PortalSiteIndex sites;
  private final boolean bareBuildAllowed;

  public PortalTransportDomain(MacroExpansionContext context) {
    this.sites = PortalSiteIndex.build(context);
    int dimensionId = MacroNodeKey.dimensionId(context.calculation().world.dimension());
    this.bareBuildAllowed =
      dimensionId == MacroNodeKey.DIMENSION_OVERWORLD ? context.capabilities().canBuildPortalPair() : dimensionId == MacroNodeKey.DIMENSION_NETHER && context.capabilities().canBuildPortal();
  }

  @Override
  public String id() {
    return "portal";
  }

  public boolean empty() {
    return sites.empty() && !bareBuildAllowed;
  }

  @Override
  public void expand(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (MacroNodeKey.anchorKey(label.node()) || MacroNodeKey.stratum(label.node()) != MacroStratum.SURFACE || !label.state().pedestrianMode()) {
      return;
    }
    int dimensionId = MacroNodeKey.dimensionId(label.node());
    if (!PortalGeometry.portalDimension(dimensionId)) {
      return;
    }
    for (PortalSite site : sites.sites(label.node())) {
      emitSite(context, site, out);
    }
    emitBareSite(context, label, out);
  }

  private static void emitSite(MacroExpansionContext context, PortalSite site, MacroOptionSink out) {
    if (site.lit()) {
      if (site.sourceDimensionId() == MacroNodeKey.DIMENSION_OVERWORLD && !context.capabilities().canBuildPortal()) {
        return;
      }
      out.accept(new MacroOption(MacroActionKind.PORTAL_ENTER, site.targetCell(), MacroAgentState.pedestrian(), MacroCostVector.fixedTime(portalUseCost()),
        List.of(site.interaction(), site.pairedEstimate()), null));
      return;
    }
    if (site.sourceDimensionId() == MacroNodeKey.DIMENSION_OVERWORLD) {
      if (!context.capabilities().canCompletePortalPair(site.missingObsidian(), PortalFrame.MINIMAL_FRAME_BLOCKS)) {
        return;
      }
      out.accept(new MacroOption(MacroActionKind.PORTAL_BUILD_ENTER, site.targetCell(), MacroAgentState.pedestrian(),
        MacroCostVector.fixedTime(portalCompletionCost(site.missingObsidian()) + portalUseCost()), List.of(site.interaction(), site.pairedEstimate()), null));
      return;
    }
    if (context.capabilities().canCompletePortal(site.missingObsidian())) {
      out.accept(new MacroOption(MacroActionKind.PORTAL_BUILD_EXIT, site.targetCell(), MacroAgentState.pedestrian(),
        MacroCostVector.fixedTime(portalCompletionCost(site.missingObsidian()) + portalUseCost()), List.of(site.interaction(), site.pairedEstimate()), null));
    }
  }

  private static void emitBareSite(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    int dimensionId = MacroNodeKey.dimensionId(label.node());
    if (dimensionId == MacroNodeKey.DIMENSION_OVERWORLD && !context.capabilities().canBuildPortalPair()) {
      return;
    }
    if (dimensionId == MacroNodeKey.DIMENSION_NETHER && !context.capabilities().canBuildPortal()) {
      return;
    }
    BetterBlockPos here = nodeCenter(context, label.node());
    int targetDimensionId = PortalGeometry.pairedDimension(dimensionId);
    BetterBlockPos there = PortalGeometry.transform(here, dimensionId, targetDimensionId);
    long target =
      MacroNodeKey.cell(targetDimensionId, MacroStratum.SURFACE, context.atlas().scale(), Math.floorDiv(there.x, context.atlas().cellBlocks()), Math.floorDiv(there.z, context.atlas().cellBlocks()));
    MacroActionKind kind = dimensionId == MacroNodeKey.DIMENSION_NETHER ? MacroActionKind.PORTAL_BUILD_EXIT : MacroActionKind.PORTAL_BUILD_ENTER;
    out.accept(
      new MacroOption(kind, target, MacroAgentState.pedestrian(), MacroCostVector.fixedTime(portalCompletionCost(PortalFrame.MINIMAL_FRAME_BLOCKS) + portalUseCost()), List.of(here, there), null));
  }

  private static BetterBlockPos nodeCenter(MacroExpansionContext context, long node) {
    return MacroNodeKey.center(node, context.physicalStart().y);
  }

  private static double portalUseCost() {
    return Baritone.settings().macroNetherPortalUseCost.value;
  }

  private static double portalCompletionCost(int missingObsidian) {
    if (missingObsidian == 0) {
      return ignitionCost();
    }
    return ignitionCost() + Baritone.settings().macroNetherPortalBuildCost.value * missingObsidian / (double) PortalFrame.MINIMAL_FRAME_BLOCKS;
  }

  private static double ignitionCost() {
    return Math.max(DEFAULT_IGNITION_TICKS, Baritone.settings().macroNetherPortalUseCost.value * 0.5D);
  }
}
