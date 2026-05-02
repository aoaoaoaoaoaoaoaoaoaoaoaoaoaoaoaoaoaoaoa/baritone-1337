package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

final class PathNodeArena {
  private static final int ABSENT = -1;

  private final Goal goal;
  private final Long2IntOpenHashMap indexByKey;
  private PathNode[] nodes;
  private int size;

  PathNodeArena(Goal goal, int expectedSize, float loadFactor) {
    int capacity = Math.max(16, expectedSize);
    this.goal = goal;
    this.indexByKey = new Long2IntOpenHashMap(capacity, loadFactor);
    this.indexByKey.defaultReturnValue(ABSENT);
    this.nodes = new PathNode[capacity];
  }

  PathNode getOrCreate(int x, int y, int z, long blockKey) {
    int index = indexByKey.get(blockKey);
    if (index != ABSENT) {
      return nodes[index];
    }
    return createAbsent(x, y, z, blockKey);
  }

  PathNode createAbsent(int x, int y, int z, long blockKey) {
    int index = size;
    if (index == nodes.length) {
      nodes = Arrays.copyOf(nodes, nodes.length << 1);
    }
    PathNode node = new PathNode(x, y, z, goal);
    nodes[index] = node;
    indexByKey.put(blockKey, index);
    size = index + 1;
    return node;
  }

  PathNode peek(long blockKey) {
    int index = indexByKey.get(blockKey);
    return index == ABSENT ? null : nodes[index];
  }

  int size() {
    return size;
  }
}
