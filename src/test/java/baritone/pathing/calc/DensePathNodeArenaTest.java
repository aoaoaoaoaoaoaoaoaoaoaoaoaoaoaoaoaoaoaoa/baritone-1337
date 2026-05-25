package baritone.pathing.calc;

import static org.junit.Assert.assertEquals;

import baritone.api.pathing.goals.Goal;
import java.util.HashMap;
import org.junit.Test;

public class DensePathNodeArenaTest {
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
  public void packedKeysSurviveCollisionsAndRehash() {
    DensePathNodeArena arena = new DensePathNodeArena(ZERO_GOAL, 16, 0.75f, 20_000);
    HashMap<Long, Integer> ids = new HashMap<>();
    for (int x = -37; x <= 37; x += 3) {
      for (int y = -8; y <= 120; y += 8) {
        for (int z = -41; z <= 41; z += 5) {
          long key = BlockKey.pack(x, y, z);
          int id = arena.createAbsent(x, y, z, key);
          ids.put(key, id);
        }
      }
    }
    ids.forEach((key, id) -> {
      assertEquals((int) id, arena.peek(key));
      assertEquals(BlockKey.x(key), arena.x(id));
      assertEquals(BlockKey.y(key), arena.y(id));
      assertEquals(BlockKey.z(key), arena.z(id));
    });
  }
}
