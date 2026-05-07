package baritone.pathing.macro.value;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class DStarLiteValueFieldTest {
  @Test
  public void computesFrontierTerminalValueOverInMemoryPriorMap() {
    Grid grid = new Grid(7, 5);
    for (int z = 0; z < 5; z++) {
      grid.cost(3, z, 100D);
    }
    grid.cost(3, 0, 1D);

    DStarLiteValueField field = new DStarLiteValueField(grid, grid.key(0, 2));
    field.setTerminal(grid.key(6, 2), 0D);
    DStarLiteValueField.RepairResult result = field.repairFully();

    assertTrue(result.startConsistent());
    assertEquals(10D, field.value(grid.key(0, 2)), 0D);
    assertFalse(path(field, grid, 0, 2).contains(grid.key(3, 2)));
    assertTrue(path(field, grid, 0, 2).contains(grid.key(3, 0)));
  }

  @Test
  public void repairsAfterCostIncreaseWithoutBlankSlateReplan() {
    Grid grid = new Grid(7, 5);
    for (int z = 0; z < 5; z++) {
      grid.cost(3, z, 100D);
    }
    grid.cost(3, 0, 1D);
    grid.cost(3, 4, 2D);

    DStarLiteValueField field = new DStarLiteValueField(grid, grid.key(0, 2));
    field.setTerminal(grid.key(6, 2), 0D);
    field.repairFully();
    assertTrue(path(field, grid, 0, 2).contains(grid.key(3, 0)));

    grid.cost(3, 0, 200D);
    grid.invalidateColumn(field, 3);
    DStarLiteValueField.RepairResult repaired = field.repairFully();

    assertTrue(repaired.startConsistent());
    assertTrue(field.value(grid.key(0, 2)) > 10D);
    assertFalse(path(field, grid, 0, 2).contains(grid.key(3, 0)));
    assertTrue(path(field, grid, 0, 2).contains(grid.key(3, 4)));
  }

  @Test
  public void terminalCostsModelLoadedBoundaryContinuationValues() {
    Grid grid = new Grid(6, 3);
    DStarLiteValueField field = new DStarLiteValueField(grid, grid.key(0, 1));
    field.setTerminal(grid.key(2, 1), 20D);
    field.setTerminal(grid.key(5, 1), 0D);
    field.repairFully();

    assertEquals(5D, field.value(grid.key(0, 1)), 0D);
    assertEquals(grid.key(1, 1), field.bestSuccessor(grid.key(0, 1)).orElseThrow());
  }

  @Test
  public void repairAllMakesArbitraryBoundaryValuesQueryable() {
    Grid grid = new Grid(9, 3);
    DStarLiteValueField field = new DStarLiteValueField(grid, grid.key(0, 1));
    field.setTerminal(grid.key(8, 1), 0D);
    DStarLiteValueField.RepairResult result = field.repairAll();

    assertTrue(result.startConsistent());
    assertEquals(8D, field.value(grid.key(0, 1)), 0D);
    assertEquals(3D, field.value(grid.key(5, 1)), 0D);
    assertEquals(5D, field.value(grid.key(3, 1)), 0D);
  }

  @Test
  public void repairAllKeepsOneResidentQueueEntryPerStateOnLargeEightWayGrids() {
    Grid grid = new Grid(72, 72, Grid.EIGHT_WAY);
    DStarLiteValueField field = new DStarLiteValueField(grid, grid.key(0, 0));
    field.setTerminal(grid.key(71, 71), 0D);
    DStarLiteValueField.RepairResult result = field.repairAll();

    assertTrue(result.startConsistent());
    assertEquals(0, result.queued());
    assertEquals(71D * Math.sqrt(2D), field.value(grid.key(0, 0)), 1.0e-9);
    assertTrue("each grid cell should be settled at most a small constant number of times, got " + result.queuePops(), result.queuePops() < 72 * 72 * 8);
  }

  private static List<Long> path(DStarLiteValueField field, Grid grid, int x, int z) {
    ArrayList<Long> path = new ArrayList<>();
    long cursor = grid.key(x, z);
    for (int i = 0; i < grid.width * grid.depth; i++) {
      path.add(cursor);
      var next = field.bestSuccessor(cursor);
      if (next.isEmpty()) {
        return path;
      }
      cursor = next.getAsLong();
    }
    fail("value field cycle: " + path);
    return path;
  }

  private static final class Grid implements DynamicValueGraph {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[][] EIGHT_WAY = {{1, -1, 0, 0, 1, 1, -1, -1}, {0, 0, 1, -1, 1, -1, 1, -1}};
    private final int width;
    private final int depth;
    private final int[] dx;
    private final int[] dz;
    private final Long2DoubleOpenHashMap cost = new Long2DoubleOpenHashMap();

    private Grid(int width, int depth) {
      this(width, depth, new int[][]{DX, DZ});
    }

    private Grid(int width, int depth, int[][] neighborhood) {
      this.width = width;
      this.depth = depth;
      this.dx = neighborhood[0];
      this.dz = neighborhood[1];
      cost.defaultReturnValue(1D);
    }

    private long key(int x, int z) {
      return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private int x(long key) {
      return (int) (key >> 32);
    }

    private int z(long key) {
      return (int) key;
    }

    private void cost(int x, int z, double value) {
      cost.put(key(x, z), value);
    }

    private void invalidateColumn(DStarLiteValueField field, int x) {
      for (int z = 0; z < depth; z++) {
        field.invalidate(key(x, z));
      }
    }

    @Override
    public void successors(long state, EdgeSink out) {
      int x = x(state);
      int z = z(state);
      for (int i = 0; i < dx.length; i++) {
        int nx = x + dx[i];
        int nz = z + dz[i];
        if (nx >= 0 && nx < width && nz >= 0 && nz < depth) {
          long next = key(nx, nz);
          out.accept(next, stepCost(x, z, nx, nz) * cost.get(next));
        }
      }
    }

    @Override
    public void predecessors(long state, EdgeSink out) {
      int x = x(state);
      int z = z(state);
      for (int i = 0; i < dx.length; i++) {
        int px = x + dx[i];
        int pz = z + dz[i];
        if (px >= 0 && px < width && pz >= 0 && pz < depth) {
          out.accept(key(px, pz), stepCost(px, pz, x, z) * cost.get(state));
        }
      }
    }

    @Override
    public double heuristic(long from, long to) {
      int ax = Math.abs(x(from) - x(to));
      int az = Math.abs(z(from) - z(to));
      if (dx.length == EIGHT_WAY[0].length) {
        int diagonal = Math.min(ax, az);
        int straight = Math.max(ax, az) - diagonal;
        return diagonal * Math.sqrt(2D) + straight;
      }
      return ax + az;
    }

    private static double stepCost(int x, int z, int nx, int nz) {
      return x != nx && z != nz ? Math.sqrt(2D) : 1D;
    }
  }
}
