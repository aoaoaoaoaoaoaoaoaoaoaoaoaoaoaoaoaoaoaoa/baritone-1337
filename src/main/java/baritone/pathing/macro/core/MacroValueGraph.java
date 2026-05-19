package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.value.DynamicValueGraph;

final class MacroValueGraph implements DynamicValueGraph {
  private static final int[] D = {-1, 0, 1};

  private MacroAtlas atlas;
  private final MacroPolicy policy;
  private final MacroTraversalProfile profile;
  private final int minCellX;
  private final int maxCellX;
  private final int minCellZ;
  private final int maxCellZ;
  private final boolean floor;

  MacroValueGraph(MacroAtlas atlas, MacroPolicy policy, MacroTraversalProfile profile, int minCellX, int maxCellX, int minCellZ, int maxCellZ, boolean floor) {
    this.atlas = atlas;
    this.policy = policy;
    this.profile = profile;
    this.minCellX = minCellX;
    this.maxCellX = maxCellX;
    this.minCellZ = minCellZ;
    this.maxCellZ = maxCellZ;
    this.floor = floor;
  }

  void retarget(MacroAtlas atlas) {
    this.atlas = atlas;
  }

  int minCellX() {
    return minCellX;
  }

  int maxCellX() {
    return maxCellX;
  }

  int minCellZ() {
    return minCellZ;
  }

  int maxCellZ() {
    return maxCellZ;
  }

  @Override
  public void successors(long state, EdgeSink out) {
    neighbors(state, false, out);
  }

  @Override
  public void predecessors(long state, EdgeSink out) {
    neighbors(state, true, out);
  }

  @Override
  public double heuristic(long from, long to) {
    return Math.hypot(MacroNodeKey.cellX(from) - MacroNodeKey.cellX(to), MacroNodeKey.cellZ(from) - MacroNodeKey.cellZ(to)) * atlas.cellBlocks() * Baritone.settings().costHeuristic.value;
  }

  private void neighbors(long state, boolean predecessor, EdgeSink out) {
    if (MacroNodeKey.anchorKey(state) || MacroNodeKey.stratum(state) != MacroStratum.SURFACE) {
      return;
    }
    int x = MacroNodeKey.cellX(state);
    int z = MacroNodeKey.cellZ(state);
    for (int dx : D) {
      for (int dz : D) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        int nx = x + dx;
        int nz = z + dz;
        if (nx < minCellX || nx > maxCellX || nz < minCellZ || nz > maxCellZ) {
          continue;
        }
        int costCellX = predecessor ? x : nx;
        int costCellZ = predecessor ? z : nz;
        long next = MacroNodeKey.cell(atlas.dimension(), MacroStratum.SURFACE, atlas.scale(), nx, nz);
        out.accept(next, edgeCost(x, z, nx, nz, costCellX, costCellZ));
      }
    }
  }

  private double edgeCost(int x, int z, int nx, int nz, int costCellX, int costCellZ) {
    BetterBlockPos from = atlas.center(x, z);
    BetterBlockPos to = atlas.center(nx, nz);
    double distance = Math.hypot(to.x - from.x, to.z - from.z);
    if (floor) {
      double expected = policy.score(profile.surfaceCost(atlas, costCellX, costCellZ, distance), profile.canonicalSurfaceState(), profile.canonicalSurfaceState());
      return Math.min(expected, distance * profile.lowerBoundTicksPerBlock(atlas.context()));
    }
    return policy.score(profile.surfaceCost(atlas, costCellX, costCellZ, distance), profile.canonicalSurfaceState(), profile.canonicalSurfaceState());
  }
}
