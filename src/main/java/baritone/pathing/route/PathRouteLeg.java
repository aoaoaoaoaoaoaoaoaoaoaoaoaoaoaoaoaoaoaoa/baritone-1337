package baritone.pathing.route;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public record PathRouteLeg(IPath path, boolean surfaceOverlay, PlannedTransportState entryState, PlannedTransportState exitState) implements RouteLeg {
  public PathRouteLeg(IPath path) {
    this(path, false, PlannedTransportState.pedestrian(false), PlannedTransportState.pedestrian(false));
  }

  public static PathRouteLeg legacy(IPath path, PlannedTransportState state) {
    return new PathRouteLeg(path, true, state, state);
  }

  public static PathRouteLeg pedestrian(IPath path, PlannedTransportState entry, PlannedTransportState exit) {
    return new PathRouteLeg(path, false, entry, exit);
  }

  public static PathRouteLeg connector(IPath path, PlannedTransportState entry, PlannedTransportState exit) {
    return new PathRouteLeg(path, true, entry, exit);
  }

  @Override
  public BetterBlockPos src() {
    return path.getSrc();
  }

  @Override
  public BetterBlockPos dest() {
    return path.getDest();
  }

  @Override
  public double estimatedTicks() {
    return path.ticksRemainingFrom(0);
  }

  @Override
  public boolean contains(BlockPos feet) {
    return path.positions().contains(feet);
  }

  @Override
  public List<BetterBlockPos> validPositions() {
    return path.positions();
  }

  @Override
  public List<BetterBlockPos> renderPositions() {
    return path.positions();
  }

  @Override
  public Optional<IPath> legacyPath() {
    return Optional.of(path);
  }
}
