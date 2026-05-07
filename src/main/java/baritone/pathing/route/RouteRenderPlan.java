package baritone.pathing.route;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;

public record RouteRenderPlan(List<Segment> segments, List<Anchor> anchors) {
  public RouteRenderPlan {
    segments = List.copyOf(segments);
    anchors = List.copyOf(anchors);
  }

  public record Segment(TransportMode mode, boolean terminal, boolean active, int componentId, List<BetterBlockPos> positions, int startIndex) {
    public Segment {
      positions = List.copyOf(positions);
    }
  }

  public record Anchor(AnchorKind kind, BetterBlockPos pos) {
  }

  public enum AnchorKind {
    LAUNCH, ENTRY, LANDING, CHOKE, FRONTIER, TERMINAL
  }
}
