package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.meso.portal.PortalTaskTarget;

public record PortalSite(PortalSiteKind kind, BetterBlockPos interaction, BetterBlockPos pairedEstimate, long sourceCell, long targetCell, int sourceDimensionId, int targetDimensionId,
  int missingObsidian, PortalTaskTarget target) {
  public PortalSite {
    if (target == null) {
      throw new IllegalArgumentException("portal site requires a meso task target");
    }
    if (missingObsidian < 0 || missingObsidian > PortalFrame.MINIMAL_FRAME_BLOCKS) {
      throw new IllegalArgumentException("invalid missing obsidian for portal site: " + missingObsidian);
    }
    if (target.missingObsidian() != missingObsidian || target.siteKind() != kind || !target.anchor().equals(interaction) || !target.pairedEstimate().equals(pairedEstimate)) {
      throw new IllegalArgumentException("portal site target disagrees with site fields: " + target + " site=" + kind + "@" + interaction);
    }
  }

  public boolean lit() {
    return kind == PortalSiteKind.LIT_PORTAL;
  }
}
