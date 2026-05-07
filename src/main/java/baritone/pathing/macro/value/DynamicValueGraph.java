package baritone.pathing.macro.value;

public interface DynamicValueGraph {
  void successors(long state, EdgeSink out);

  void predecessors(long state, EdgeSink out);

  default double heuristic(long from, long to) {
    return 0D;
  }

  @FunctionalInterface
  interface EdgeSink {
    void accept(long state, double cost);
  }
}
