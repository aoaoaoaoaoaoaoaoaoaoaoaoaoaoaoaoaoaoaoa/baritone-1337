package baritone.pathing.transport;

import baritone.api.utils.BetterBlockPos;
import java.util.Objects;

public record TransportLeg(TransportMode mode, BetterBlockPos src, BetterBlockPos entry, BetterBlockPos dest, TransportTerminality terminality) {
  public TransportLeg {
    Objects.requireNonNull(mode);
    Objects.requireNonNull(src);
    Objects.requireNonNull(entry);
    Objects.requireNonNull(dest);
    Objects.requireNonNull(terminality);
  }

  public boolean terminal() {
    return terminality.terminal();
  }

  public TransportMode nextMode() {
    return terminal() ? TransportMode.PEDESTRIAN : mode;
  }
}
