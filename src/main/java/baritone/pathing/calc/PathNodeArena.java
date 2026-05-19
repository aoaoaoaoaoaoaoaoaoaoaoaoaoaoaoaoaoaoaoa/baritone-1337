package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import it.unimi.dsi.fastutil.HashCommon;

final class PathNodeArena {
  private final Goal goal;
  private final float loadFactor;
  private long[] keys;
  private PathNode[] values;
  private int mask;
  private int maxFill;
  private int size;

  PathNodeArena(Goal goal, int expectedSize, float loadFactor) {
    this.goal = goal;
    this.loadFactor = loadFactor;
    int capacity = HashCommon.arraySize(Math.max(16, expectedSize), loadFactor);
    this.keys = new long[capacity];
    this.values = new PathNode[capacity];
    this.mask = capacity - 1;
    this.maxFill = HashCommon.maxFill(capacity, loadFactor);
  }

  PathNode getOrCreate(int x, int y, int z, long blockKey) {
    PathNode node = get(blockKey);
    if (node != null) {
      return node;
    }
    return createAbsent(x, y, z, blockKey);
  }

  PathNode createAbsent(int x, int y, int z, long blockKey) {
    PathNode node = new PathNode(x, y, z, goal);
    putKnownAbsent(blockKey, node);
    return node;
  }

  PathNode peek(long blockKey) {
    return get(blockKey);
  }

  int size() {
    return size;
  }

  private PathNode get(long key) {
    int pos = (int) HashCommon.mix(key) & mask;
    PathNode value;
    while ((value = values[pos]) != null) {
      if (keys[pos] == key) {
        return value;
      }
      pos = pos + 1 & mask;
    }
    return null;
  }

  private void putKnownAbsent(long key, PathNode value) {
    if (size + 1 > maxFill) {
      rehash(keys.length << 1);
    }
    int pos = (int) HashCommon.mix(key) & mask;
    while (values[pos] != null) {
      if (keys[pos] == key) {
        throw new IllegalStateException("duplicate path node key " + key);
      }
      pos = pos + 1 & mask;
    }
    keys[pos] = key;
    values[pos] = value;
    size++;
  }

  private void rehash(int capacity) {
    long[] oldKeys = keys;
    PathNode[] oldValues = values;
    keys = new long[capacity];
    values = new PathNode[capacity];
    mask = capacity - 1;
    maxFill = HashCommon.maxFill(capacity, loadFactor);
    int oldSize = size;
    size = 0;
    for (int i = 0; i < oldValues.length; i++) {
      PathNode value = oldValues[i];
      if (value != null) {
        int pos = (int) HashCommon.mix(oldKeys[i]) & mask;
        while (values[pos] != null) {
          pos = pos + 1 & mask;
        }
        keys[pos] = oldKeys[i];
        values[pos] = value;
        size++;
      }
    }
    if (size != oldSize) {
      throw new IllegalStateException("path node arena rehash lost entries: " + size + " != " + oldSize);
    }
  }
}
