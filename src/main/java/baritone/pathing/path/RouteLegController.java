package baritone.pathing.path;

import baritone.api.utils.IPlayerContext;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Set;
import net.minecraft.core.BlockPos;

interface RouteLegController {
  boolean onTick();

  boolean failed();

  boolean finished();

  int getPosition();

  int size();

  ControlFrame controlFrame();

  TransportControl transportControl();

  TransportSnapshot.Plan transportPlan(IPlayerContext ctx);

  default double estimatedTicksRemaining(RouteLeg leg) {
    return leg.estimatedTicks();
  }

  boolean containsPathPosition(BlockPos pos);

  Set<BlockPos> toBreak();

  Set<BlockPos> toPlace();

  Set<BlockPos> toWalkInto();

  boolean isSprinting();
}
