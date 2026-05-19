package baritone.pathing.route;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.transport.TransportMode;
import java.util.List;

public record RouteRenderPlan(List<Segment> segments, List<Anchor> anchors) {
  public RouteRenderPlan {
    segments = List.copyOf(segments);
    anchors = List.copyOf(anchors);
  }

  public record Segment(TransportMode mode, boolean terminal, boolean active, int componentId, List<BetterBlockPos> positions, int startIndex, SegmentKind kind, SegmentRole role) {
    public Segment(TransportMode mode, boolean terminal, boolean active, int componentId, List<BetterBlockPos> positions, int startIndex, SegmentKind kind) {
      this(mode, terminal, active, componentId, positions, startIndex, kind, active ? SegmentRole.COMMITTED : SegmentRole.CANDIDATE);
    }

    public Segment(TransportMode mode, boolean terminal, boolean active, int componentId, List<BetterBlockPos> positions, int startIndex) {
      this(mode, terminal, active, componentId, positions, startIndex, SegmentKind.NORMAL);
    }

    public Segment {
      positions = List.copyOf(positions);
      if (kind == null) {
        throw new IllegalArgumentException("route render segment kind must not be null");
      }
      if (role == null) {
        throw new IllegalArgumentException("route render segment role must not be null");
      }
    }
  }

  public enum SegmentKind {
    NORMAL, RELAXED
  }

  public enum SegmentRole {
    COMMITTED, CANDIDATE
  }

  public record Anchor(AnchorKind kind, BetterBlockPos pos) {
  }

  public enum AnchorKind {
    LAUNCH, ENTRY, LANDING, CHOKE, FRONTIER, TERMINAL
  }
}
