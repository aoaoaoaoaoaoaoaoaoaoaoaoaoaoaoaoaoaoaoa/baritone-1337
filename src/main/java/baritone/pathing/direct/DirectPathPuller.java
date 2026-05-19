package baritone.pathing.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleBiFunction;

public final class DirectPathPuller {
  private DirectPathPuller() {
  }

  public static <N, E extends DirectEdge<N>> PullResult<N> pull(List<N> path, DirectPullSchedule schedule, ToDoubleBiFunction<N, N> metric, DirectEdgeOracle<N, E> oracle) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(schedule);
    Objects.requireNonNull(metric);
    Objects.requireNonNull(oracle);
    int n = path.size();
    if (n < 3) {
      return new PullResult<>(path, false);
    }
    double[] prefix = prefix(path, metric);
    ArrayList<N> pulled = new ArrayList<>(n);
    pulled.add(path.getFirst());
    boolean changed = false;
    int from = 0;
    while (from < n - 1) {
      Candidate<N, E> candidate = farthestCertified(path, prefix, schedule, oracle, from);
      if (candidate == null) {
        pulled.add(path.get(from + 1));
        from++;
      } else {
        pulled.add(candidate.edge().to());
        changed = true;
        from = candidate.to();
      }
    }
    return changed ? new PullResult<>(pulled, true) : new PullResult<>(path, false);
  }

  private static <N> double[] prefix(List<N> path, ToDoubleBiFunction<N, N> metric) {
    double[] prefix = new double[path.size()];
    for (int i = 1; i < path.size(); i++) {
      double step = metric.applyAsDouble(path.get(i - 1), path.get(i));
      if (!Double.isFinite(step) || step < 0D) {
        throw new IllegalArgumentException("direct pull metric must be finite and nonnegative at edge " + (i - 1) + ": " + step);
      }
      prefix[i] = prefix[i - 1] + step;
    }
    return prefix;
  }

  private static <N, E extends DirectEdge<N>> Candidate<N, E> farthestCertified(List<N> path, double[] prefix, DirectPullSchedule schedule, DirectEdgeOracle<N, E> oracle, int from) {
    double base = prefix[from];
    for (int rung = 0; rung < schedule.spanCount(); rung++) {
      double span = schedule.span(rung);
      int to = upperBound(prefix, base + span);
      if (to <= from + 1) {
        continue;
      }
      Optional<E> certified = oracle.certify(path.get(from), path.get(to));
      if (certified.isEmpty()) {
        continue;
      }
      Candidate<N, E> best = new Candidate<>(to, certified.get());
      int crawlLimit = upperBound(prefix, Math.min(base + span + schedule.crawlBlocks(), base + schedule.maxSpan()));
      for (int crawl = to + 1; crawl <= crawlLimit; crawl++) {
        Optional<E> crawled = oracle.certify(path.get(from), path.get(crawl));
        if (crawled.isEmpty()) {
          break;
        }
        best = new Candidate<>(crawl, crawled.get());
      }
      return best;
    }
    return null;
  }

  private static int upperBound(double[] prefix, double limit) {
    int lo = 0;
    int hi = prefix.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (prefix[mid] <= limit) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return Math.max(0, lo - 1);
  }

  public record PullResult<N>(List<N> path, boolean changed) {
    public PullResult {
      path = List.copyOf(path);
    }
  }

  private record Candidate<N, E extends DirectEdge<N>>(int to, E edge) {
  }
}
