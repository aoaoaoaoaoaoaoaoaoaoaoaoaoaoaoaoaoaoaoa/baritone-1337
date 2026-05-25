package baritone.pathing.calc.openset;

import baritone.api.pathing.goals.Goal;
import baritone.pathing.calc.PathNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.*;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class OpenSetsTest {
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

  public OpenSetsTest(int size) {
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

  private static void removeAndTest(BinaryHeapOpenSet heap, int amount, Collection<PathNode> mustContain, double previous) {
    for (int i = 0; i < amount; i++) {
      PathNode pn = heap.removeLowest();
      if (mustContain != null) {
        assertTrue(mustContain.contains(pn));
      }
      assertTrue(previous <= pn.combinedCost);
      previous = pn.combinedCost;
    }
  }

  @Test
  public void testSize() {
    BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
    assertTrue(heap.isEmpty());
    Random random = new Random(size);

    PathNode[] toInsert = new PathNode[size];
    for (int i = 0; i < size; i++) {
      PathNode pn = new PathNode(0, 0, 0, ZERO_GOAL);
      pn.combinedCost = random.nextDouble();
      toInsert[i] = pn;
    }

    ArrayList<PathNode> copy = new ArrayList<>(Arrays.asList(toInsert));
    copy.sort(Comparator.comparingDouble(pn -> pn.combinedCost));
    Set<PathNode> lowestQuarter = new HashSet<>(copy.subList(0, size / 4));

    assertTrue(heap.isEmpty());
    for (int i = 0; i < size; i++) heap.insert(toInsert[i]);

    assertFalse(heap.isEmpty());

    removeAndTest(heap, size / 4, lowestQuarter, Double.NEGATIVE_INFINITY);

    assertFalse(heap.isEmpty());
    int cnt = 0;
    for (int i = 0; cnt < size / 2 && i < size; i++) {
      if (lowestQuarter.contains(toInsert[i])) { // these were already removed and can't be updated to test
        continue;
      }
      toInsert[i].combinedCost *= random.nextDouble();
      // Decrease-key must be called immediately after mutation; batching would intentionally corrupt heap order.
      heap.update(toInsert[i]);
      cnt++;
    }

    assertFalse(heap.isEmpty());
    removeAndTest(heap, size - size / 4, null, Double.NEGATIVE_INFINITY);

    assertTrue(heap.isEmpty());
  }
}
