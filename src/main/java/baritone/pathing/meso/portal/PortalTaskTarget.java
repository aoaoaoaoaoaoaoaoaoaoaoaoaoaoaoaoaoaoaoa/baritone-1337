package baritone.pathing.meso.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.portal.PortalFrame;
import baritone.pathing.macro.portal.PortalSiteKind;

public sealed interface PortalTaskTarget permits PortalTaskTarget.LitPortal, PortalTaskTarget.Frame, PortalTaskTarget.BareRegion {
  BetterBlockPos anchor();

  BetterBlockPos pairedEstimate();

  PortalSiteKind siteKind();

  int missingObsidian();

  record LitPortal(BetterBlockPos anchor, BetterBlockPos pairedEstimate) implements PortalTaskTarget {
    public LitPortal {
      if (anchor == null || pairedEstimate == null) {
        throw new IllegalArgumentException("lit portal target requires anchor and paired estimate");
      }
    }

    @Override
    public PortalSiteKind siteKind() {
      return PortalSiteKind.LIT_PORTAL;
    }

    @Override
    public int missingObsidian() {
      return 0;
    }
  }

  record Frame(PortalFrame.FrameMatch frame, BetterBlockPos pairedEstimate) implements PortalTaskTarget {
    public Frame {
      if (frame == null || pairedEstimate == null) {
        throw new IllegalArgumentException("portal frame target requires a frame and paired estimate");
      }
    }

    @Override
    public BetterBlockPos anchor() {
      return frame.lowerLeftInterior();
    }

    @Override
    public PortalSiteKind siteKind() {
      return frame.kind();
    }

    @Override
    public int missingObsidian() {
      return frame.missingObsidian();
    }
  }

  record BareRegion(BetterBlockPos anchor, BetterBlockPos pairedEstimate) implements PortalTaskTarget {
    public BareRegion {
      if (anchor == null || pairedEstimate == null) {
        throw new IllegalArgumentException("bare portal target requires anchor and paired estimate");
      }
    }

    @Override
    public PortalSiteKind siteKind() {
      return PortalSiteKind.BARE_REGION;
    }

    @Override
    public int missingObsidian() {
      return PortalFrame.MINIMAL_FRAME_BLOCKS;
    }
  }
}
