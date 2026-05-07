package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;

public record PortalSite(PortalSiteKind kind, BetterBlockPos interaction, BetterBlockPos pairedEstimate, long sourceCell, long targetCell, int sourceDimensionId, int targetDimensionId,
  int missingObsidian) {
  public PortalSite {
    if (missingObsidian < 0 || missingObsidian > PortalFrame.MINIMAL_FRAME_BLOCKS) {
      throw new IllegalArgumentException("invalid missing obsidian for portal site: " + missingObsidian);
    }
  }

  public boolean lit() {
    return kind == PortalSiteKind.LIT_PORTAL;
  }
}
