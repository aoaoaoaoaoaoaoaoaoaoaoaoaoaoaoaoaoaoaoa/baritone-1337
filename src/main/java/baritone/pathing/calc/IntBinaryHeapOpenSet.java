package baritone.pathing.calc;

import java.util.Arrays;

final class IntBinaryHeapOpenSet {
  private static final int INITIAL_CAPACITY = 1024;

  private final DensePathNodeArena nodes;
  private int[] heap;
  private int size;

  IntBinaryHeapOpenSet(DensePathNodeArena nodes) {
    this(nodes, INITIAL_CAPACITY);
  }

  IntBinaryHeapOpenSet(DensePathNodeArena nodes, int capacity) {
    this.nodes = nodes;
    heap = new int[capacity];
  }

  int size() {
    return size;
  }

  boolean isEmpty() { return size == 0; }

  double lowestCombinedCost() {
    return size == 0 ? Double.POSITIVE_INFINITY : nodes.combinedCost(heap[1]);
  }

  void insert(int node) {
    if (size >= heap.length - 1) {
      heap = Arrays.copyOf(heap, heap.length << 1);
    }
    heap[++size] = node;
    nodes.heapPosition(node, size);
    update(node);
  }

  void update(int node) {
    int index = nodes.heapPosition(node);
    int parentIndex = index >>> 1;
    double cost = nodes.combinedCost(node);
    int parent = heap[parentIndex];
    while (index > 1 && nodes.combinedCost(parent) > cost) {
      heap[index] = parent;
      heap[parentIndex] = node;
      nodes.heapPosition(node, parentIndex);
      nodes.heapPosition(parent, index);
      index = parentIndex;
      parentIndex = index >>> 1;
      parent = heap[parentIndex];
    }
  }

  int removeLowest() {
    if (size == 0) {
      throw new IllegalStateException("Cannot remove from empty heap");
    }
    int result = heap[1];
    int node = heap[size];
    heap[1] = node;
    nodes.heapPosition(node, 1);
    heap[size] = 0;
    size--;
    nodes.heapPosition(result, 0);
    if (size < 2) {
      return result;
    }
    int index = 1;
    int smallerChild = 2;
    double cost = nodes.combinedCost(node);
    do {
      int smallerChildNode = heap[smallerChild];
      double smallerChildCost = nodes.combinedCost(smallerChildNode);
      if (smallerChild < size) {
        int rightChildNode = heap[smallerChild + 1];
        double rightChildCost = nodes.combinedCost(rightChildNode);
        if (smallerChildCost > rightChildCost) {
          smallerChild++;
          smallerChildCost = rightChildCost;
          smallerChildNode = rightChildNode;
        }
      }
      if (cost <= smallerChildCost) {
        break;
      }
      heap[index] = smallerChildNode;
      heap[smallerChild] = node;
      nodes.heapPosition(node, smallerChild);
      nodes.heapPosition(smallerChildNode, index);
      index = smallerChild;
    } while ((smallerChild <<= 1) <= size);
    return result;
  }
}
