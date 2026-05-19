package baritone.pathing.meso.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroCostVector;
import baritone.pathing.macro.portal.PortalFrame;
import baritone.pathing.meso.ExecutableMesoTaskPlan;
import java.util.List;

public sealed interface PortalTaskPlan extends ExecutableMesoTaskPlan permits PortalTaskPlan.EnterExisting, PortalTaskPlan.BuildPortal {
  PortalTaskIntent intent();

  record EnterExisting(PortalTaskIntent intent, BetterBlockPos portalBlock, MacroCostVector estimate, List<BetterBlockPos> renderPositions) implements PortalTaskPlan {
    public EnterExisting {
      if (intent == null || portalBlock == null || estimate == null || renderPositions == null || renderPositions.isEmpty()) {
        throw new IllegalArgumentException("existing portal plan requires intent, portal block, estimate, and render positions");
      }
      if (intent.kind() != PortalTaskKind.ENTER_EXISTING) {
        throw new IllegalArgumentException("existing portal plan cannot execute " + intent.kind());
      }
      renderPositions = List.copyOf(renderPositions);
    }

    @Override
    public BetterBlockPos approachAnchor() {
      return portalBlock;
    }
  }

  record BuildPortal(PortalTaskIntent intent, PortalFrame.FrameMatch frame, MacroCostVector estimate, List<BetterBlockPos> renderPositions) implements PortalTaskPlan {
    public BuildPortal {
      if (intent == null || frame == null || estimate == null || renderPositions == null || renderPositions.isEmpty()) {
        throw new IllegalArgumentException("portal build plan requires intent, frame, estimate, and render positions");
      }
      if (intent.kind() == PortalTaskKind.ENTER_EXISTING) {
        throw new IllegalArgumentException("portal build plan cannot execute " + intent.kind());
      }
      renderPositions = List.copyOf(renderPositions);
    }

    @Override
    public BetterBlockPos approachAnchor() {
      return frame.lowerLeftInterior();
    }
  }
}
