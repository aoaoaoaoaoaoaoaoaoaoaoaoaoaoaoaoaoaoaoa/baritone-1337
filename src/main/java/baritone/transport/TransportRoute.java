package baritone.transport;

import baritone.api.utils.BetterBlockPos;
import java.util.List;

public interface TransportRoute {
  TransportKind kind();

  List<BetterBlockPos> waypoints();

  boolean complete();
}
