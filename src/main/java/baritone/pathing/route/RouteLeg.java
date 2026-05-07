package baritone.pathing.route;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public sealed interface RouteLeg permits PathRouteLeg, SurfaceRouteLeg {
  BetterBlockPos src();

  BetterBlockPos dest();

  double estimatedTicks();

  PlannedTransportState entryState();

  PlannedTransportState exitState();

  default TransportMode entryMode() {
    return entryState().mode();
  }

  default TransportMode exitMode() {
    return exitState().mode();
  }

  boolean contains(BlockPos feet);

  List<BetterBlockPos> validPositions();

  List<BetterBlockPos> renderPositions();

  default Optional<IPath> legacyPath() {
    return Optional.empty();
  }
}
