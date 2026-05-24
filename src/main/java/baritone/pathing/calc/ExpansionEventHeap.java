package baritone.pathing.calc;

import java.util.Arrays;

final class ExpansionEventHeap {
  private static final int INITIAL_CAPACITY = 1024;

  private PathNode[] source = new PathNode[INITIAL_CAPACITY];
  private int[] generation = new int[INITIAL_CAPACITY];
  private int[] serial = new int[INITIAL_CAPACITY];
  private short[] primitiveIndex = new short[INITIAL_CAPACITY];
  private double[] actionLowerBound = new double[INITIAL_CAPACITY];
  private double[] key = new double[INITIAL_CAPACITY];
  private int[] tie = new int[INITIAL_CAPACITY];
  private int size;

  boolean isEmpty() { return size == 0; }

  int size() {
    return size;
  }

  double lowestKey() {
    return size == 0 ? Double.POSITIVE_INFINITY : key[1];
  }

  void insert(PathNode node, int primitiveIndex, double lowerBound, double priorityKey, int tieBreak) {
    if (size >= source.length - 1) {
      grow();
    }
    int slot = ++size;
    source[slot] = node;
    generation[slot] = node.expansionGeneration;
    serial[slot] = ++node.expansionSerial;
    this.primitiveIndex[slot] = (short) primitiveIndex;
    actionLowerBound[slot] = lowerBound;
    key[slot] = priorityKey;
    tie[slot] = tieBreak;
    siftUp(slot);
  }

  void removeLowest(Event out) {
    if (size == 0) {
      throw new IllegalStateException("Cannot remove from empty expansion event heap");
    }
    out.source = source[1];
    out.generation = generation[1];
    out.serial = serial[1];
    out.primitiveIndex = primitiveIndex[1] & 0xFFFF;
    out.actionLowerBound = actionLowerBound[1];
    out.key = key[1];
    out.tie = tie[1];
    move(size, 1);
    clear(size);
    size--;
    if (size > 1) {
      siftDown(1);
    }
  }

  private void siftUp(int slot) {
    while (slot > 1) {
      int parent = slot >>> 1;
      if (lessOrEqual(parent, slot)) {
        return;
      }
      swap(parent, slot);
      slot = parent;
    }
  }

  private void siftDown(int slot) {
    while (true) {
      int left = slot << 1;
      if (left > size) {
        return;
      }
      int child = left;
      int right = left + 1;
      if (right <= size && less(right, left)) {
        child = right;
      }
      if (lessOrEqual(slot, child)) {
        return;
      }
      swap(slot, child);
      slot = child;
    }
  }

  private boolean lessOrEqual(int a, int b) {
    return key[a] < key[b] || key[a] == key[b] && tie[a] <= tie[b];
  }

  private boolean less(int a, int b) {
    return key[a] < key[b] || key[a] == key[b] && tie[a] < tie[b];
  }

  private void swap(int a, int b) {
    PathNode sourceA = source[a];
    int generationA = generation[a];
    int serialA = serial[a];
    short primitiveA = primitiveIndex[a];
    double lowerA = actionLowerBound[a];
    double keyA = key[a];
    int tieA = tie[a];
    move(b, a);
    source[b] = sourceA;
    generation[b] = generationA;
    serial[b] = serialA;
    primitiveIndex[b] = primitiveA;
    actionLowerBound[b] = lowerA;
    key[b] = keyA;
    tie[b] = tieA;
  }

  private void move(int from, int to) {
    source[to] = source[from];
    generation[to] = generation[from];
    serial[to] = serial[from];
    primitiveIndex[to] = primitiveIndex[from];
    actionLowerBound[to] = actionLowerBound[from];
    key[to] = key[from];
    tie[to] = tie[from];
  }

  private void clear(int slot) {
    source[slot] = null;
    generation[slot] = 0;
    serial[slot] = 0;
    primitiveIndex[slot] = 0;
    actionLowerBound[slot] = 0;
    key[slot] = 0;
    tie[slot] = 0;
  }

  private void grow() {
    int next = source.length << 1;
    source = Arrays.copyOf(source, next);
    generation = Arrays.copyOf(generation, next);
    serial = Arrays.copyOf(serial, next);
    primitiveIndex = Arrays.copyOf(primitiveIndex, next);
    actionLowerBound = Arrays.copyOf(actionLowerBound, next);
    key = Arrays.copyOf(key, next);
    tie = Arrays.copyOf(tie, next);
  }

  static final class Event {
    PathNode source;
    int generation;
    int serial;
    int primitiveIndex;
    double actionLowerBound;
    double key;
    int tie;

    boolean stale() {
      return source.expansionGeneration != generation || source.expansionSerial != serial;
    }
  }
}
