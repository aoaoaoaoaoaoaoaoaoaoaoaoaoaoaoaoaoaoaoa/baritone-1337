package baritone.pathing.calc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.pathing.goals.Goal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class IntBinaryHeapOpenSetTest {
  private static final Goal ZERO_GOAL = new Goal() {
    @Override
    public boolean isInGoal(int x, int y, int z) {
      return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
      return 0;
    }
  };

  private final int size;

  public IntBinaryHeapOpenSetTest(int size) {
    this.size = size;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    ArrayList<Object[]> testSizes = new ArrayList<>();
    for (int size = 1; size < 20; size++) {
      testSizes.add(new Object[]{size});
    }
    for (int size = 100; size <= 1000; size += 100) {
      testSizes.add(new Object[]{size});
    }
    testSizes.add(new Object[]{5000});
    testSizes.add(new Object[]{10000});
    return testSizes;
  }

  @Test
  public void orderedRemovalAndDecreaseKey() {
    DensePathNodeArena arena = new DensePathNodeArena(ZERO_GOAL, size + 1, 0.75f, size + 1);
    IntBinaryHeapOpenSet heap = new IntBinaryHeapOpenSet(arena);
    assertTrue(heap.isEmpty());
    Random random = new Random(size);

    int[] nodes = new int[size];
    for (int i = 0; i < size; i++) {
      int node = arena.createAbsent(i, 0, 0, BlockKey.pack(i, 0, 0));
      arena.combinedCost(node, random.nextDouble());
      nodes[i] = node;
    }

    ArrayList<Integer> copy = new ArrayList<>(size);
    for (int node : nodes) {
      copy.add(node);
    }
    copy.sort(Comparator.comparingDouble(arena::combinedCost));
    Set<Integer> lowestQuarter = new HashSet<>(copy.subList(0, size / 4));

    assertTrue(heap.isEmpty());
    for (int node : nodes) {
      heap.insert(node);
    }

    assertFalse(heap.isEmpty());
    removeAndTest(arena, heap, size / 4, lowestQuarter, Double.NEGATIVE_INFINITY);

    assertFalse(heap.isEmpty());
    int cnt = 0;
    for (int node : nodes) {
      if (cnt >= size / 2) {
        break;
      }
      if (lowestQuarter.contains(node)) {
        continue;
      }
      arena.combinedCost(node, arena.combinedCost(node) * random.nextDouble());
      heap.update(node);
      cnt++;
    }

    assertFalse(heap.isEmpty());
    removeAndTest(arena, heap, size - size / 4, null, Double.NEGATIVE_INFINITY);

    assertTrue(heap.isEmpty());
  }

  private static double removeAndTest(DensePathNodeArena arena, IntBinaryHeapOpenSet heap, int amount, Collection<Integer> mustContain, double previous) {
    for (int i = 0; i < amount; i++) {
      int node = heap.removeLowest();
      if (mustContain != null) {
        assertTrue(mustContain.contains(node));
      }
      assertTrue(previous <= arena.combinedCost(node));
      previous = arena.combinedCost(node);
    }
    return previous;
  }
}
