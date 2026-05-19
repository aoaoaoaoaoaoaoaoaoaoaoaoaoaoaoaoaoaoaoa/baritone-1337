package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroActionKind;
import baritone.pathing.macro.core.MacroAgentState;
import baritone.pathing.macro.core.MacroDomain;
import baritone.pathing.macro.core.MacroExpansionContext;
import baritone.pathing.macro.core.MacroLabel;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroOption;
import baritone.pathing.macro.core.MacroOptionSink;
import baritone.pathing.macro.core.MacroStratum;
import baritone.pathing.meso.MesoTaskQuote;
import baritone.pathing.meso.MesoTaskRequest;
import baritone.pathing.meso.portal.PortalLinkConstraint;
import baritone.pathing.meso.portal.PortalMesoSiter;
import baritone.pathing.meso.portal.PortalSafetyPolicy;
import baritone.pathing.meso.portal.PortalTaskIntent;
import baritone.pathing.meso.portal.PortalTaskKind;
import baritone.pathing.meso.portal.PortalTaskTarget;
import java.util.List;

public final class PortalTransportDomain implements MacroDomain {
  private static final PortalMesoSiter SITER = new PortalMesoSiter();
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
      PortalTaskIntent intent = intent(PortalTaskKind.ENTER_EXISTING, site.target());
      out.accept(new MacroOption(MacroActionKind.PORTAL_ENTER, site.targetCell(), MacroAgentState.pedestrian(), quote(context, intent).expected(), List.of(site.interaction(), site.pairedEstimate()),
        null, intent));
      return;
    }
    if (site.sourceDimensionId() == MacroNodeKey.DIMENSION_OVERWORLD) {
      if (!context.capabilities().canCompletePortalPair(site.missingObsidian(), PortalFrame.MINIMAL_FRAME_BLOCKS)) {
        return;
      }
      PortalTaskIntent intent = intent(PortalTaskKind.BUILD_ENTER, site.target());
      out.accept(new MacroOption(MacroActionKind.PORTAL_BUILD_ENTER, site.targetCell(), MacroAgentState.pedestrian(), quote(context, intent).expected(),
        List.of(site.interaction(), site.pairedEstimate()), null, intent));
      return;
    }
    if (context.capabilities().canCompletePortal(site.missingObsidian())) {
      PortalTaskIntent intent = intent(PortalTaskKind.BUILD_EXIT, site.target());
      out.accept(new MacroOption(MacroActionKind.PORTAL_BUILD_EXIT, site.targetCell(), MacroAgentState.pedestrian(), quote(context, intent).expected(),
        List.of(site.interaction(), site.pairedEstimate()), null, intent));
    }
  }

  private void emitBareSite(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    int dimensionId = MacroNodeKey.dimensionId(label.node());
    if (dimensionId == MacroNodeKey.DIMENSION_OVERWORLD && !context.capabilities().canBuildPortalPair()) {
      return;
    }
    if (dimensionId == MacroNodeKey.DIMENSION_NETHER && !context.capabilities().canBuildPortal()) {
      return;
    }
    BetterBlockPos here = bareBuildAnchor(context, label.node());
    if (sites.wouldRelinkToKnownPortal(here, dimensionId)) {
      return;
    }
    int targetDimensionId = PortalGeometry.pairedDimension(dimensionId);
    BetterBlockPos there = PortalGeometry.transform(here, dimensionId, targetDimensionId);
    long target =
      MacroNodeKey.cell(targetDimensionId, MacroStratum.SURFACE, context.atlas().scale(), Math.floorDiv(there.x, context.atlas().cellBlocks()), Math.floorDiv(there.z, context.atlas().cellBlocks()));
    MacroActionKind kind = dimensionId == MacroNodeKey.DIMENSION_NETHER ? MacroActionKind.PORTAL_BUILD_EXIT : MacroActionKind.PORTAL_BUILD_ENTER;
    PortalTaskIntent intent = intent(kind == MacroActionKind.PORTAL_BUILD_EXIT ? PortalTaskKind.BUILD_EXIT : PortalTaskKind.BUILD_ENTER, new PortalTaskTarget.BareRegion(here, there));
    out.accept(new MacroOption(kind, target, MacroAgentState.pedestrian(), quote(context, intent).expected(), List.of(here, there), null, intent));
  }

  private static BetterBlockPos nodeCenter(MacroExpansionContext context, long node) {
    return MacroNodeKey.center(node, context.physicalStart().y);
  }

  private static BetterBlockPos bareBuildAnchor(MacroExpansionContext context, long node) {
    BetterBlockPos start = context.physicalStart();
    int cellBlocks = context.atlas().cellBlocks();
    int sx = Math.floorDiv(start.x, cellBlocks);
    int sz = Math.floorDiv(start.z, cellBlocks);
    if (MacroNodeKey.dimensionId(node) == MacroNodeKey.dimensionId(context.calculation().world.dimension()) && MacroNodeKey.cellX(node) == sx && MacroNodeKey.cellZ(node) == sz) {
      return start;
    }
    return nodeCenter(context, node);
  }

  private static PortalTaskIntent intent(PortalTaskKind kind, PortalTaskTarget target) {
    return new PortalTaskIntent(kind, target, PortalLinkConstraint.AVOID_RETURN_TO_SOURCE, PortalSafetyPolicy.ORDINARY);
  }

  private static MesoTaskQuote quote(MacroExpansionContext context, PortalTaskIntent intent) {
    return SITER.quoteFast(new MesoTaskRequest<>(context.calculation(), context.physicalStart(), context.capabilities(), context.policy(), intent));
  }
}
