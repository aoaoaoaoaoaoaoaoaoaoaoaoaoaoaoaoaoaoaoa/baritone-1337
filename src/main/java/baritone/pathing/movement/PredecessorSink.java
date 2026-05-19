package baritone.pathing.movement;

@FunctionalInterface
public interface PredecessorSink {
  void accept(int x, int y, int z);
}
