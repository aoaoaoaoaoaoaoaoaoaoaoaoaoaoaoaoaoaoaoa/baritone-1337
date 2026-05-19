package baritone.pathing.direct;

import java.util.Arrays;

public record DirectPullSchedule(double crawlBlocks, double[] spans) {
  public DirectPullSchedule {
    if (!Double.isFinite(crawlBlocks) || crawlBlocks < 0D) {
      throw new IllegalArgumentException("crawl distance must be finite and nonnegative: " + crawlBlocks);
    }
    spans = spans.clone();
    if (spans.length == 0) {
      throw new IllegalArgumentException("direct pull schedule needs at least one span");
    }
    double previous = Double.POSITIVE_INFINITY;
    for (double span : spans) {
      if (!Double.isFinite(span) || span <= 0D) {
        throw new IllegalArgumentException("pull span must be finite and positive: " + span);
      }
      if (span >= previous) {
        throw new IllegalArgumentException("pull spans must be strictly descending: " + Arrays.toString(spans));
      }
      previous = span;
    }
  }

  public static DirectPullSchedule descending(double crawlBlocks, double... spans) {
    return new DirectPullSchedule(crawlBlocks, spans);
  }

  public double maxSpan() {
    return spans[0] + crawlBlocks;
  }

  @Override
  public double[] spans() {
    return spans.clone();
  }

  public int spanCount() {
    return spans.length;
  }

  public double span(int index) {
    return spans[index];
  }
}
