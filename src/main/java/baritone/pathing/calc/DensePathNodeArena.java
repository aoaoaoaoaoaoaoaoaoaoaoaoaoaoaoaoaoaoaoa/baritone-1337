package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.SettingsUtil;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;

final class DensePathNodeArena {
  // Hot state is columnar by design: one int node id names the whole row. Positions stay packed;
  // h/f are cached because heap ordering and best-so-far pound them harder than memory bandwidth does.
  private final Goal goal;
  private final float loadFactor;
  private final int maxSize;
  private final int maxCapacity;
  private long[] tableKeys;
  private int[] tableIds;
  private int mask;
  private int maxFill;

  private long[] keys;
  private double[] cost;
  private double[] heuristic;
  private double[] combinedCost;
  private int[] previous;
  private int[] previousEdge;
  private int[] heapPosition;
  private int size;

  DensePathNodeArena(Goal goal, int expectedSize, float loadFactor, int maxSize) {
    this.goal = goal;
    this.loadFactor = loadFactor;
    this.maxSize = Math.max(16, maxSize);
    this.maxCapacity = HashCommon.arraySize(this.maxSize, loadFactor);
    int tableCapacity = Math.min(HashCommon.arraySize(Math.max(16, expectedSize), loadFactor), maxCapacity);
    tableKeys = new long[tableCapacity];
    tableIds = new int[tableCapacity];
    mask = tableCapacity - 1;
    maxFill = HashCommon.maxFill(tableCapacity, loadFactor);
    int nodeCapacity = Math.min(Math.max(17, expectedSize + 1), this.maxSize + 1);
    keys = new long[nodeCapacity];
    cost = new double[nodeCapacity];
    heuristic = new double[nodeCapacity];
    combinedCost = new double[nodeCapacity];
    previous = new int[nodeCapacity];
    previousEdge = new int[nodeCapacity];
    heapPosition = new int[nodeCapacity];
  }

  int getOrCreate(int x, int y, int z, long blockKey) {
    int node = get(blockKey);
    return node == 0 ? createAbsent(x, y, z, blockKey) : node;
  }

  int createAbsent(int x, int y, int z, long blockKey) {
    if (full()) {
      throw new IllegalStateException("path node arena cap exceeded: " + maxSize);
    }
    ensureNodeCapacity(size + 2);
    int id = ++size;
    keys[id] = blockKey;
    cost[id] = ActionCosts.COST_INF;
    double h = goal.heuristic(x, y, z);
    if (Double.isNaN(h)) {
      throw new IllegalStateException(
        String.format("%s calculated implausible heuristic NaN at %s %s %s", goal, SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z)));
    }
    heuristic[id] = h;
    putKnownAbsent(blockKey, id);
    return id;
  }

  int peek(long blockKey) {
    return get(blockKey);
  }

  int size() {
    return size;
  }

  boolean full() {
    return size >= maxSize;
  }

  int x(int node) {
    return BlockKey.x(keys[node]);
  }

  int y(int node) {
    return BlockKey.y(keys[node]);
  }

  int z(int node) {
    return BlockKey.z(keys[node]);
  }

  double cost(int node) {
    return cost[node];
  }

  void cost(int node, double value) {
    cost[node] = value;
  }

  double heuristic(int node) {
    return heuristic[node];
  }

  double combinedCost(int node) {
    return combinedCost[node];
  }

  void combinedCost(int node, double value) {
    combinedCost[node] = value;
  }

  int previous(int node) {
    return previous[node];
  }

  int previousPrimitiveIndex(int node) {
    return previousEdge[node] >>> 16;
  }

  int previousEdgePayload(int node) {
    return previousEdge[node] & 0xFFFF;
  }

  void predecessor(int node, int previous, int primitiveIndex, int edgePayload) {
    if ((primitiveIndex | edgePayload) >>> 16 != 0) {
      throw new IllegalArgumentException("path edge metadata exceeds 16-bit packing: primitive=" + primitiveIndex + " payload=" + edgePayload);
    }
    this.previous[node] = previous;
    this.previousEdge[node] = primitiveIndex << 16 | edgePayload;
  }

  int heapPosition(int node) {
    return heapPosition[node];
  }

  void heapPosition(int node, int position) {
    heapPosition[node] = position;
  }

  boolean isOpen(int node) {
    return heapPosition[node] != 0;
  }

  private int get(long key) {
    int pos = slot(key);
    int id;
    while ((id = tableIds[pos]) != 0) {
      if (tableKeys[pos] == key) {
        return id;
      }
      pos = pos + 1 & mask;
    }
    return 0;
  }

  private void putKnownAbsent(long key, int id) {
    if (id > maxFill) {
      rehash(Math.min(tableKeys.length << 1, maxCapacity));
    }
    int pos = slot(key);
    while (tableIds[pos] != 0) {
      if (tableKeys[pos] == key) {
        throw new IllegalStateException("duplicate path node key " + key);
      }
      pos = pos + 1 & mask;
    }
    tableKeys[pos] = key;
    tableIds[pos] = id;
  }

  private void ensureNodeCapacity(int required) {
    if (required <= keys.length) {
      return;
    }
    int next = Math.min(maxSize + 1, Math.max(required, keys.length << 1));
    keys = Arrays.copyOf(keys, next);
    cost = Arrays.copyOf(cost, next);
    heuristic = Arrays.copyOf(heuristic, next);
    combinedCost = Arrays.copyOf(combinedCost, next);
    previous = Arrays.copyOf(previous, next);
    previousEdge = Arrays.copyOf(previousEdge, next);
    heapPosition = Arrays.copyOf(heapPosition, next);
  }

  private int slot(long key) {
    // BlockKey leaves z in the low bits; fold x/y down before the one golden-ratio multiply.
    long h = key ^ key >>> 26 ^ key >>> 38;
    h *= 0x9E3779B97F4A7C15L;
    return (int) (h ^ h >>> 32) & mask;
  }

  private void rehash(int capacity) {
    long[] oldKeys = tableKeys;
    int[] oldIds = tableIds;
    tableKeys = new long[capacity];
    tableIds = new int[capacity];
    mask = capacity - 1;
    maxFill = HashCommon.maxFill(capacity, loadFactor);
    int expectedEntries = size - 1; // called just before inserting the freshly allocated id
    int entries = 0;
    for (int i = 0; i < oldIds.length; i++) {
      int id = oldIds[i];
      if (id != 0) {
        int pos = slot(oldKeys[i]);
        while (tableIds[pos] != 0) {
          pos = pos + 1 & mask;
        }
        tableKeys[pos] = oldKeys[i];
        tableIds[pos] = id;
        entries++;
      }
    }
    if (entries != expectedEntries) {
      throw new IllegalStateException("path node arena rehash lost entries: " + entries + " != " + expectedEntries);
    }
  }
}
