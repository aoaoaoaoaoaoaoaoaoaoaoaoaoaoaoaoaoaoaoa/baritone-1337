package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportLeg;
import baritone.pathing.transport.TransportMode;
import java.util.List;

public record WaterLineSegment(TransportLeg leg, BetterBlockPos waterEnd, WaterLineProfile profile, double length, double cost, List<BetterBlockPos> validPositions) {
  public WaterLineSegment {
    validPositions = List.copyOf(validPositions);
    if (length < 0D || !Double.isFinite(length)) {
      throw new IllegalArgumentException("water line length must be finite and nonnegative: " + length);
    }
    if (cost < 0D || !Double.isFinite(cost)) {
      throw new IllegalArgumentException("water line cost must be finite and nonnegative: " + cost);
    }
  }

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
