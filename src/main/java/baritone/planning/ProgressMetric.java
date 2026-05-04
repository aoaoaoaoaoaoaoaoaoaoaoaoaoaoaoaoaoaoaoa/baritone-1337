package baritone.planning;

public sealed interface ProgressMetric permits ProgressMetric.PathIndex, ProgressMetric.LineProjection, ProgressMetric.None {
  record PathIndex() implements ProgressMetric {
  }

  record LineProjection(WorldCell start, WorldCell end) implements ProgressMetric {
  }

  record None() implements ProgressMetric {
  }
}
