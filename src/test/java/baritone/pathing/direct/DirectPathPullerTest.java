package baritone.pathing.direct;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DirectPathPullerTest {
  @Test
  public void crawlsPastSuccessfulRungWithoutAssumingMonotoneFailure() {
    List<Integer> path = new ArrayList<>(61);
    for (int i = 0; i <= 60; i++) {
      path.add(i);
    }

    DirectEdgeOracle<Integer, Edge> oracle = (from, to) -> Math.abs(to - from) <= 55 ? Optional.of(new Edge(from, to, Math.abs(to - from))) : Optional.empty();

    DirectPathPuller.PullResult<Integer> pulled = DirectPathPuller.pull(path, DirectPullSchedule.descending(10D, 48D, 32D, 16D), (a, b) -> Math.abs(b - a), oracle);

    assertTrue(pulled.changed());
    assertEquals(List.of(0, 55, 60), pulled.path());
  }

  private record Edge(Integer from, Integer to, double cost) implements DirectEdge<Integer> {
  }
}
