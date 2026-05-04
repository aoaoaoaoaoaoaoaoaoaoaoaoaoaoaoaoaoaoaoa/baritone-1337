package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportLeg;
import baritone.pathing.transport.TransportMode;
import java.util.List;

public record WaterLineSegment(TransportLeg leg, BetterBlockPos waterEnd, WaterLineProfile profile, double length, double cost, List<BetterBlockPos> validPositions) {
  public BetterBlockPos src() {
    return leg.src();
  }

  public BetterBlockPos waterStart() {
    return leg.entry();
  }

  public BetterBlockPos dest() {
    return leg.dest();
  }

  public TransportMode mode() {
    return leg.mode();
  }

  public boolean terminal() {
    return leg.terminal();
  }

  public boolean boat() {
    return mode().boat();
  }
}
