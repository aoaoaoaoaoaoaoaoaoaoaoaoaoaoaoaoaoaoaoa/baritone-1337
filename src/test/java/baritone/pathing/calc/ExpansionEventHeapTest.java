package baritone.pathing.calc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.pathing.goals.Goal;
import org.junit.Test;

public class ExpansionEventHeapTest {
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

  @Test
  public void ordersByKeyThenTie() {
    PathNode a = new PathNode(0, 64, 0, ZERO_GOAL);
    PathNode b = new PathNode(1, 64, 0, ZERO_GOAL);
    a.resetExpansion(4);
    b.resetExpansion(4);
    ExpansionEventHeap heap = new ExpansionEventHeap();
    heap.insert(a, 2, 1, 10, 20);
    heap.insert(b, 1, 1, 5, 30);
    heap.insert(a, 3, 1, 10, 10);
    ExpansionEventHeap.Event event = new ExpansionEventHeap.Event();
    heap.removeLowest(event);
    assertEquals(b, event.source);
    assertEquals(1, event.primitiveIndex);
    assertEquals(5, event.key, 0);
    heap.removeLowest(event);
    assertEquals(a, event.source);
    assertEquals(3, event.primitiveIndex);
    assertEquals(10, event.key, 0);
  }

  @Test
  public void generationAndSerialDetectStaleEvents() {
    PathNode node = new PathNode(0, 64, 0, ZERO_GOAL);
    node.resetExpansion(4);
    ExpansionEventHeap heap = new ExpansionEventHeap();
    heap.insert(node, 0, 1, 1, 0);
    node.resetExpansion(4);
    ExpansionEventHeap.Event event = new ExpansionEventHeap.Event();
    heap.removeLowest(event);
    assertTrue(event.stale());
    heap.insert(node, 1, 1, 2, 0);
    heap.removeLowest(event);
    assertFalse(event.stale());
    assertEquals(1, event.primitiveIndex);
  }
}
