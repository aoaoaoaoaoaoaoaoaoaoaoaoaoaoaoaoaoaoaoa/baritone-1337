package baritone.pathing.meso.portal;

import static org.junit.Assert.assertEquals;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroCostVector;
import baritone.pathing.macro.portal.PortalFrame;
import baritone.pathing.macro.portal.PortalSiteKind;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.Test;

public class PortalTaskTargetTest {
  @Test
  public void frameTargetPreservesExactMesoSitingFacts() {
    PortalFrame.FrameMatch frame = new PortalFrame.FrameMatch(new BetterBlockPos(10, 64, -5), Direction.Axis.Z, 7, 3, 6);
    BetterBlockPos paired = new BetterBlockPos(80, 64, -40);
    PortalTaskTarget.Frame target = new PortalTaskTarget.Frame(frame, paired);

    assertEquals(new BetterBlockPos(10, 64, -5), target.anchor());
    assertEquals(Direction.Axis.Z, target.frame().axis());
    assertEquals(PortalSiteKind.PARTIAL_FRAME, target.siteKind());
    assertEquals(3, target.missingObsidian());
    assertEquals(paired, target.pairedEstimate());
  }

  @Test
  public void bareRegionIsNotMisrepresentedAsPartialFrame() {
    PortalTaskTarget.BareRegion target = new PortalTaskTarget.BareRegion(new BetterBlockPos(0, 64, 0), new BetterBlockPos(0, 64, 0));

    assertEquals(PortalSiteKind.BARE_REGION, target.siteKind());
    assertEquals(PortalFrame.MINIMAL_FRAME_BLOCKS, target.missingObsidian());
  }

  @Test
  public void buildPlanPreservesConcreteFrameGeometry() {
    PortalFrame.FrameMatch frame = new PortalFrame.FrameMatch(new BetterBlockPos(20, 70, 30), Direction.Axis.X, 0, PortalFrame.MINIMAL_FRAME_BLOCKS, 0);
    PortalTaskIntent intent = new PortalTaskIntent(PortalTaskKind.BUILD_EXIT, new PortalTaskTarget.BareRegion(frame.lowerLeftInterior(), new BetterBlockPos(160, 70, 240)),
      PortalLinkConstraint.AVOID_RETURN_TO_SOURCE, PortalSafetyPolicy.ORDINARY);
    PortalTaskPlan.BuildPortal plan = new PortalTaskPlan.BuildPortal(intent, frame, MacroCostVector.fixedTime(1D), List.of(frame.lowerLeftInterior()));

    assertEquals(frame.lowerLeftInterior(), plan.approachAnchor());
    assertEquals(Direction.Axis.X, plan.frame().axis());
  }
}
