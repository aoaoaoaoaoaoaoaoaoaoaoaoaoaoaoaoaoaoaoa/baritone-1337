package baritone.pathing.macro.value;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.OptionalLong;
import java.util.PriorityQueue;

public final class DStarLiteValueField {
  private final DynamicValueGraph graph;
  private final Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
  private final Long2DoubleOpenHashMap rhs = new Long2DoubleOpenHashMap();
  private final Long2DoubleOpenHashMap terminal = new Long2DoubleOpenHashMap();
  private final PriorityQueue<QueueEntry> queue = new PriorityQueue<>();
  private final Long2ObjectOpenHashMap<Priority> residentKeys = new Long2ObjectOpenHashMap<>();
  private long start;
  private double km;

  public DStarLiteValueField(DynamicValueGraph graph, long start) {
    this.graph = graph;
    this.start = start;
    g.defaultReturnValue(Double.POSITIVE_INFINITY);
    rhs.defaultReturnValue(Double.POSITIVE_INFINITY);
    terminal.defaultReturnValue(Double.POSITIVE_INFINITY);
  }

  public void moveStart(long nextStart) {
    if (nextStart == start) {
      return;
    }
    km += graph.heuristic(start, nextStart);
    start = nextStart;
  }

  public long start() {
    return start;
  }

  public void setTerminal(long state, double terminalCost) {
    if (!Double.isFinite(terminalCost) || terminalCost < 0D) {
      throw new IllegalArgumentException("terminal cost must be finite and nonnegative: " + terminalCost);
    }
    terminal.put(state, terminalCost);
    updateVertex(state);
    graph.predecessors(state, (predecessor, ignored) -> updateVertex(predecessor));
  }

  public void removeTerminal(long state) {
    terminal.remove(state);
    updateVertex(state);
    graph.predecessors(state, (predecessor, ignored) -> updateVertex(predecessor));
  }

  public void invalidate(long state) {
    updateVertex(state);
    graph.predecessors(state, (predecessor, ignored) -> updateVertex(predecessor));
  }

  public RepairResult repairFully() {
    return repair(Integer.MAX_VALUE);
  }

  public RepairResult repairAll() {
    int pops = 0;
    for (QueueEntry entry = pollValid(); entry != null; entry = pollValid()) {
      pops++;
      repairState(entry.state());
    }
    return new RepairResult(!shouldRepairStart(), pops, residentKeys.size(), value(start));
  }

  public RepairResult repair(int maxQueuePops) {
    int pops = 0;
    while (pops < maxQueuePops && shouldRepairStart()) {
      QueueEntry entry = pollValid();
      if (entry == null) {
        break;
      }
      pops++;
      repairState(entry.state());
    }
    return new RepairResult(!shouldRepairStart(), pops, residentKeys.size(), value(start));
  }

  public double value(long state) {
    return g.get(state);
  }

  public boolean consistent(long state) {
    return same(g.get(state), rhs.get(state));
  }

  public OptionalLong bestSuccessor(long state) {
    BestEdge best = bestSuccessorEdge(state);
    if (terminal.get(state) <= best.cost()) {
      return OptionalLong.empty();
    }
    return best.state() == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(best.state());
  }

  private boolean shouldRepairStart() {
    QueueEntry top = peekValid();
    return top != null && top.key().compareTo(key(start)) < 0 || !same(rhs.get(start), g.get(start));
  }

  private void updateVertex(long state) {
    double best = terminal.get(state);
    BestEdge edge = bestSuccessorEdge(state);
    if (edge.state() != Long.MIN_VALUE) {
      best = Math.min(best, edge.cost());
    }
    put(rhs, state, best);
    if (same(g.get(state), best)) {
      residentKeys.remove(state);
      return;
    }
    Priority priority = key(state);
    Priority resident = residentKeys.get(state);
    if (!priority.equals(resident)) {
      residentKeys.put(state, priority);
      queue.add(new QueueEntry(state, priority));
    }
  }

  private void repairState(long state) {
    double gState = g.get(state);
    double rhsState = rhs.get(state);
    if (gState > rhsState) {
      put(g, state, rhsState);
      graph.predecessors(state, (predecessor, ignored) -> updateVertex(predecessor));
    } else {
      g.remove(state);
      updateVertex(state);
      graph.predecessors(state, (predecessor, ignored) -> updateVertex(predecessor));
    }
  }

  private BestEdge bestSuccessorEdge(long state) {
    BestEdge[] best = {new BestEdge(Long.MIN_VALUE, Double.POSITIVE_INFINITY)};
    graph.successors(state, (successor, cost) -> {
      if (cost < 0D || Double.isNaN(cost)) {
        throw new IllegalArgumentException("edge cost must be nonnegative: " + cost);
      }
      double candidate = cost + g.get(successor);
      if (candidate < best[0].cost()) {
        best[0] = new BestEdge(successor, candidate);
      }
    });
    return best[0];
  }

  private Priority key(long state) {
    double min = Math.min(g.get(state), rhs.get(state));
    return new Priority(min + graph.heuristic(start, state) + km, min);
  }

  private QueueEntry peekValid() {
    while (!queue.isEmpty()) {
      QueueEntry entry = queue.peek();
      Priority resident = residentKeys.get(entry.state());
      if (entry.key().equals(resident) && entry.key().compareTo(key(entry.state())) == 0) {
        return entry;
      }
      queue.poll();
    }
    return null;
  }

  private QueueEntry pollValid() {
    QueueEntry entry = peekValid();
    if (entry != null) {
      queue.poll();
      residentKeys.remove(entry.state());
    }
    return entry;
  }

  private static void put(Long2DoubleOpenHashMap map, long key, double value) {
    if (Double.isInfinite(value)) {
      map.remove(key);
    } else {
      map.put(key, value);
    }
  }

  private static boolean same(double a, double b) {
    return a == b || Double.isInfinite(a) && Double.isInfinite(b);
  }

  public record RepairResult(boolean startConsistent, int queuePops, int queued, double startValue) {
  }

  private record BestEdge(long state, double cost) {
  }

  private record QueueEntry(long state, Priority key) implements Comparable<QueueEntry> {
    @Override
    public int compareTo(QueueEntry other) {
      return key.compareTo(other.key);
    }
  }

  private record Priority(double primary, double secondary) implements Comparable<Priority> {
    @Override
    public int compareTo(Priority other) {
      int primaryOrder = Double.compare(primary, other.primary);
      return primaryOrder != 0 ? primaryOrder : Double.compare(secondary, other.secondary);
    }
  }
}
