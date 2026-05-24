package baritone.pathing.farfield;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.CalculationContext;
import java.util.Arrays;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class FarfieldSnapshot {
  private static final int[][] NEIGHBORS = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
  private static final int STRATA = 4;
  private static final int[] NETHER_STRATUM_CUTS = {32, 64, 96};
  private static final int[] OVERWORLD_STRATUM_CUTS = {40, 68, 100};
  private static final int MAX_RENDER_VERTICES = 256;
  private static final float MAX_CENTER_DISCOUNT_BLOCKS = (float) (Math.sqrt(2D) * (FarfieldColumnKey.CELL_BLOCKS >>> 1) * 0.5D);

  private final CalculationContext context;
  private final FarfieldCosts costs;
  private final int minCellX;
  private final int maxCellX;
  private final int minCellZ;
  private final int maxCellZ;
  private final int xCount;
  private final int zCount;
  private final int targetCellX;
  private final int targetCellZ;
  private final int targetStratum;
  private final boolean horizonLimited;
  private final BlockPos finalGoal;
  private final FarfieldColumnProfile[] profiles;
  private final float[] expectedValue;
  private final float[] floorValue;
  private final float[] floorQueryLowerValue;
  private final int[] bestSucc;
  private final long signature;
  private final int liveStates;
  private final int cachedStates;
  private final int priorStates;

  private FarfieldSnapshot(CalculationContext context, FarfieldCosts costs, int minCellX, int maxCellX, int minCellZ, int maxCellZ, int targetCellX, int targetCellZ, int targetStratum,
    boolean horizonLimited, BlockPos finalGoal, FarfieldColumnProfile[] profiles, float[] expectedValue, float[] floorValue, int[] bestSucc, long signature, int liveStates, int cachedStates,
    int priorStates) {
    this.context = context;
    this.costs = costs;
    this.minCellX = minCellX;
    this.maxCellX = maxCellX;
    this.minCellZ = minCellZ;
    this.maxCellZ = maxCellZ;
    this.xCount = maxCellX - minCellX + 1;
    this.zCount = maxCellZ - minCellZ + 1;
    this.targetCellX = targetCellX;
    this.targetCellZ = targetCellZ;
    this.targetStratum = targetStratum;
    this.horizonLimited = horizonLimited;
    this.finalGoal = finalGoal;
    this.profiles = profiles;
    this.expectedValue = expectedValue;
    this.floorValue = floorValue;
    this.floorQueryLowerValue = queryLowerValues(costs, profiles, floorValue);
    this.bestSucc = bestSucc;
    this.signature = signature;
    this.liveStates = liveStates;
    this.cachedStates = cachedStates;
    this.priorStates = priorStates;
  }

  public static Optional<FarfieldSnapshot> build(CalculationContext context, BetterBlockPos start, Goal goal) {
    Optional<BlockPos> goalPos = goalPosition(goal);
    if (goalPos.isEmpty()) {
      return Optional.empty();
    }
    int cellBlocks = FarfieldColumnKey.CELL_BLOCKS;
    double fullDistance = Math.hypot(goalPos.get().getX() - start.x, goalPos.get().getZ() - start.z);
    if (fullDistance < Math.max(cellBlocks * 3D, Baritone.settings().farfieldWaypointBlocks.value)) {
      return Optional.empty();
    }
    int horizon = Math.max(cellBlocks * 4, Baritone.settings().farfieldHorizonBlocks.value);
    boolean horizonLimited = fullDistance > horizon;
    int targetX = goalPos.get().getX();
    int targetZ = goalPos.get().getZ();
    if (horizonLimited) {
      double scale = horizon / fullDistance;
      targetX = start.x + (int) Math.round((goalPos.get().getX() - start.x) * scale);
      targetZ = start.z + (int) Math.round((goalPos.get().getZ() - start.z) * scale);
    }
    int sx = Math.floorDiv(start.x, cellBlocks);
    int sz = Math.floorDiv(start.z, cellBlocks);
    int tx = Math.floorDiv(targetX, cellBlocks);
    int tz = Math.floorDiv(targetZ, cellBlocks);
    int lateral = Math.max(2, Baritone.settings().farfieldLateralCells.value);
    int minX = Math.min(sx, tx) - lateral;
    int maxX = Math.max(sx, tx) + lateral;
    int minZ = Math.min(sz, tz) - lateral;
    int maxZ = Math.max(sz, tz) + lateral;
    FarfieldCosts costs = FarfieldCosts.configured(context);
    int xCount = maxX - minX + 1;
    int zCount = maxZ - minZ + 1;
    FarfieldColumnProfile[] profiles = new FarfieldColumnProfile[xCount * zCount * STRATA];
    long signature = 1469598103934665603L;
    int liveStates = 0;
    int cachedStates = 0;
    int priorStates = 0;
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        int cell = cellIndex(x, z, minX, minZ, zCount);
        FarfieldColumnProfile[] columnProfiles = profiles(context, costs, x, z);
        for (int band = 0; band < STRATA; band++) {
          FarfieldColumnProfile profile = columnProfiles[band];
          profiles[stateIndex(cell, band)] = profile;
          switch (profile.evidence()) {
            case LIVE -> liveStates++;
            case CACHED -> cachedStates++;
            case PRIOR -> priorStates++;
          }
          signature = signature * 1099511628211L + x * 31L + z * 131L + band * 53L + profile.openSurface255() * 17L + profile.void255() * 19L + profile.lava255() * 23L + profile.solid255() * 29L
            + profile.evidence().ordinal();
        }
      }
    }
    int targetStratum = stratum(context, goalPos.get().getY());
    float[] expected = solve(context, costs, profiles, minX, maxX, minZ, maxZ, tx, tz, targetStratum, goalPos.get(), horizonLimited, false);
    float[] floor = solve(context, costs, profiles, minX, maxX, minZ, maxZ, tx, tz, targetStratum, goalPos.get(), horizonLimited, true);
    int[] bestSucc = bestSuccessors(context, costs, profiles, expected, minX, maxX, minZ, maxZ, false);
    signature = signature * 1099511628211L + tx * 37L + tz * 41L + targetStratum * 43L + (horizonLimited ? 1 : 0);
    return Optional.of(new FarfieldSnapshot(context, costs, minX, maxX, minZ, maxZ, tx, tz, targetStratum, horizonLimited, goalPos.get(), profiles, expected, floor, bestSucc, signature, liveStates,
      cachedStates, priorStates));
  }

  public double expected(FrontierExit exit) {
    return value(expectedValue, exit.boundaryX(), exit.boundaryZ(), exit.boundaryY());
  }

  public double expectedAt(int x, int y, int z) {
    return value(expectedValue, x, z, y);
  }

  public double floorAt(int x, int y, int z) {
    return value(floorValue, x, z, y);
  }

  public double floorLowerBoundAt(int x, int y, int z) {
    int index = stateIndexOfBlock(x, y, z);
    if (index < 0) {
      return Double.POSITIVE_INFINITY;
    }
    float value = floorQueryLowerValue[index];
    return Float.isFinite(value) ? value : Double.POSITIVE_INFINITY;
  }

  public double floorLowerBoundOverYRange(int x, int z, int yMin, int yMax) {
    if (yMax < yMin) {
      return 0D;
    }
    int cx = Math.floorDiv(x, FarfieldColumnKey.CELL_BLOCKS);
    int cz = Math.floorDiv(z, FarfieldColumnKey.CELL_BLOCKS);
    if (!contains(cx, cz)) {
      return Double.POSITIVE_INFINITY;
    }
    int cell = cellIndex(cx, cz);
    double best = Double.POSITIVE_INFINITY;
    for (int band = 0; band < STRATA; band++) {
      if (stratumIntersects(context, band, yMin, yMax)) {
        float value = floorQueryLowerValue[stateIndex(cell, band)];
        if (Float.isFinite(value)) {
          best = Math.min(best, value);
        }
      }
    }
    return Double.isFinite(best) ? best : Double.POSITIVE_INFINITY;
  }

  public long signature() {
    return signature;
  }

  public int liveStates() {
    return liveStates;
  }

  public int cachedStates() {
    return cachedStates;
  }

  public int priorStates() {
    return priorStates;
  }

  public int stateCount() {
    return profiles.length;
  }

  public int targetStratum() {
    return targetStratum;
  }

  public int stratumAt(int y) {
    return stratum(context, y);
  }

  public BetterBlockPos targetCenter() {
    return center(targetCellX, targetCellZ, representativeY(context, targetStratum));
  }

  public java.util.List<BetterBlockPos> skeletonFrom(BetterBlockPos start) {
    java.util.ArrayList<BetterBlockPos> path = new java.util.ArrayList<>();
    int cursor = stateIndexOfBlock(start.x, start.y, start.z);
    for (int i = 0; i < MAX_RENDER_VERTICES && cursor >= 0; i++) {
      int cell = cellIndex(cursor);
      int cx = cellX(cell);
      int cz = cellZ(cell);
      int band = stratumIndex(cursor);
      path.add(center(cx, cz, representativeY(context, band)));
      if (cx == targetCellX && cz == targetCellZ && (horizonLimited || band == targetStratum)) {
        break;
      }
      int next = bestSucc[cursor];
      if (next < 0 || next == cursor) {
        break;
      }
      cursor = next;
    }
    return path;
  }

  private double value(float[] values, int blockX, int blockZ, int exitY) {
    int index = stateIndexOfBlock(blockX, exitY, blockZ);
    if (index < 0) {
      double dx = finalGoal.getX() - blockX;
      double dz = finalGoal.getZ() - blockZ;
      return Math.hypot(dx, dz) * costs.unknownTicksPerBlock();
    }
    double value = values[index];
    if (!Float.isFinite((float) value)) {
      return Double.POSITIVE_INFINITY;
    }
    FarfieldCosts.FarfieldCost profileCost = costs.cost(profiles[index]);
    int cell = cellIndex(index);
    BetterBlockPos center = center(cellX(cell), cellZ(cell), representativeY(context, stratumIndex(index)));
    double discount = Math.hypot(blockX - center.x, blockZ - center.z) * profileCost.expectedTicksPerBlock() * 0.5D;
    return Math.max(0D, value - discount);
  }

  private static float[] queryLowerValues(FarfieldCosts costs, FarfieldColumnProfile[] profiles, float[] values) {
    // Scheduler kernel: pay the in-cell discount once, keep per-event queries to cell×stratum reads.
    float[] lower = new float[values.length];
    for (int i = 0; i < values.length; i++) {
      float value = values[i];
      if (!Float.isFinite(value)) {
        lower[i] = Float.POSITIVE_INFINITY;
        continue;
      }
      FarfieldCosts.FarfieldCost profileCost = costs.cost(profiles[i]);
      lower[i] = Math.max(0F, value - MAX_CENTER_DISCOUNT_BLOCKS * profileCost.expectedTicksPerBlock());
    }
    return lower;
  }

  private int stateIndexOfBlock(int x, int y, int z) {
    int cx = Math.floorDiv(x, FarfieldColumnKey.CELL_BLOCKS);
    int cz = Math.floorDiv(z, FarfieldColumnKey.CELL_BLOCKS);
    return contains(cx, cz) ? stateIndex(cellIndex(cx, cz), stratum(context, y)) : -1;
  }

  private int cellIndex(int cellX, int cellZ) {
    return cellIndex(cellX, cellZ, minCellX, minCellZ, zCount);
  }

  private boolean contains(int cellX, int cellZ) {
    return cellX >= minCellX && cellX <= maxCellX && cellZ >= minCellZ && cellZ <= maxCellZ;
  }

  private int cellX(int cell) {
    return minCellX + cell / zCount;
  }

  private int cellZ(int cell) {
    return minCellZ + cell % zCount;
  }

  private static int cellIndex(int cellX, int cellZ, int minCellX, int minCellZ, int zCount) {
    return (cellX - minCellX) * zCount + cellZ - minCellZ;
  }

  private static int stateIndex(int cell, int stratum) {
    return cell * STRATA + stratum;
  }

  private static int cellIndex(int state) {
    return state / STRATA;
  }

  private static int stratumIndex(int state) {
    return state % STRATA;
  }

  public static Optional<BlockPos> goalPosition(Goal goal) {
    return switch (goal) {
      case GoalBlock block -> Optional.of(block.getGoalPos());
      case GoalXZ xz -> Optional.of(new BlockPos(xz.getX(), 64, xz.getZ()));
      case IGoalRenderPos render -> Optional.of(render.getGoalPos());
      default -> Optional.empty();
    };
  }

  private static FarfieldColumnProfile[] profiles(CalculationContext context, FarfieldCosts costs, int cellX, int cellZ) {
    int baseX = cellX * FarfieldColumnKey.CELL_BLOCKS;
    int baseZ = cellZ * FarfieldColumnKey.CELL_BLOCKS;
    int q = FarfieldColumnKey.CELL_BLOCKS >>> 2;
    int[][] samples = {{baseX + 8, baseZ + 8}, {baseX + q, baseZ + q}, {baseX + 12, baseZ + q}, {baseX + q, baseZ + 12}, {baseX + 12, baseZ + 12}};
    boolean anyPathingData = false;
    boolean live = false;
    int[] supports = new int[STRATA];
    int[] lava = new int[STRATA];
    int[] solid = new int[STRATA];
    int scannedColumns = 0;
    for (int[] sample : samples) {
      var fact = context.bsi.chunkFactState(sample[0], sample[1]);
      if (!fact.pathingData()) {
        continue;
      }
      anyPathingData = true;
      live |= fact.live();
      scannedColumns++;
      ColumnScan scan = scanColumn(context, sample[0], sample[1]);
      for (int band = 0; band < STRATA; band++) {
        supports[band] += scan.supports[band];
        lava[band] += scan.lava[band];
        solid[band] += scan.solid[band];
      }
    }
    FarfieldColumnProfile[] result = new FarfieldColumnProfile[STRATA];
    if (!anyPathingData) {
      for (int band = 0; band < STRATA; band++) {
        result[band] = prior(context, costs, band);
      }
      return result;
    }
    FarfieldEvidence evidence = live ? FarfieldEvidence.LIVE : FarfieldEvidence.CACHED;
    for (int band = 0; band < STRATA; band++) {
      int y = representativeY(context, band);
      if (supports[band] > 0) {
        int open = Math.min(255, 96 + supports[band] * 32);
        int lavaP = Math.min(160, lava[band] * 6);
        int solidP = Math.min(128, Math.max(0, solid[band] / Math.max(1, scannedColumns) - 8) * 8);
        result[band] = FarfieldColumnProfile.mixed(open, Math.max(0, 255 - open - lavaP - solidP), lavaP, solidP, evidence, y);
      } else if (lava[band] > solid[band] / 2 && lava[band] > 0) {
        result[band] = FarfieldColumnProfile.lava(evidence, y);
      } else if (solid[band] > scannedColumns * 12) {
        result[band] = FarfieldColumnProfile.solid(evidence, y);
      } else {
        result[band] = FarfieldColumnProfile.voidGap(evidence, y);
      }
    }
    return result;
  }

  private static FarfieldColumnProfile prior(CalculationContext context, FarfieldCosts costs, int band) {
    int y = representativeY(context, band);
    if (context.world.dimension() == Level.END) {
      float voidProbability = switch (band) {
        case 0 -> Math.min(1F, costs.endUnknownVoidProbability() * 1.10F);
        case 3 -> Math.min(1F, costs.endUnknownVoidProbability() * 1.20F);
        default -> costs.endUnknownVoidProbability();
      };
      int gap = Math.round(voidProbability * 255F);
      return FarfieldColumnProfile.mixed(255 - gap, gap, 0, 0, FarfieldEvidence.PRIOR, y);
    }
    if (context.world.dimension() == Level.NETHER) {
      if (band == 0 || band == 3) {
        return FarfieldColumnProfile.solid(FarfieldEvidence.PRIOR, y);
      }
      int lava = Math.round(Math.min(0.80F, costs.netherUnknownLavaProbability()) * 255F);
      int solid = Math.round(Math.min(0.75F, costs.netherUnknownSolidProbability()) * 255F);
      return FarfieldColumnProfile.mixed(Math.max(0, 255 - lava - solid), 0, lava, solid, FarfieldEvidence.PRIOR, y);
    }
    return switch (band) {
      case 0 -> FarfieldColumnProfile.mixed(150, 8, 4, 93, FarfieldEvidence.PRIOR, y);
      case 3 -> FarfieldColumnProfile.mixed(120, 115, 4, 16, FarfieldEvidence.PRIOR, y);
      default -> FarfieldColumnProfile.mixed(224, 8, 8, 15, FarfieldEvidence.PRIOR, y);
    };
  }

  private static ColumnScan scanColumn(CalculationContext context, int x, int z) {
    int minY = context.world.getMinY();
    int maxY = context.world.getMaxY() - 2;
    MutableBlockPos pos = new MutableBlockPos();
    int[] supports = new int[STRATA];
    int[] lava = new int[STRATA];
    int[] solid = new int[STRATA];
    for (int y = minY; y <= maxY; y += 2) {
      int blockBand = stratum(context, y);
      BlockState state = context.get(x, y, z);
      if (state.is(Blocks.LAVA)) {
        lava[blockBand]++;
      }
      boolean colliding = !state.getCollisionShape(context.bsi.access, pos.set(x, y, z)).isEmpty();
      if (colliding) {
        solid[blockBand]++;
        BlockState feet = context.get(x, y + 1, z);
        BlockState head = context.get(x, y + 2, z);
        if (feet.getCollisionShape(context.bsi.access, pos.set(x, y + 1, z)).isEmpty() && head.getCollisionShape(context.bsi.access, pos.set(x, y + 2, z)).isEmpty() && !feet.is(Blocks.LAVA)
          && !head.is(Blocks.LAVA)) {
          supports[stratum(context, y + 1)]++;
        }
      }
    }
    return new ColumnScan(supports, lava, solid);
  }

  private static float[] solve(CalculationContext context, FarfieldCosts costs, FarfieldColumnProfile[] profiles, int minX, int maxX, int minZ, int maxZ, int targetX, int targetZ, int targetStratum,
    BlockPos finalGoal, boolean horizonLimited, boolean floor) {
    int zCount = maxZ - minZ + 1;
    int cellCount = (maxX - minX + 1) * zCount;
    int count = cellCount * STRATA;
    float[] value = new float[count];
    Arrays.fill(value, Float.POSITIVE_INFINITY);
    PriorityQueue<QueueCell> queue = new PriorityQueue<>();
    int target = cellIndex(targetX, targetZ, minX, minZ, zCount);
    if (horizonLimited) {
      for (int band = 0; band < STRATA; band++) {
        int state = stateIndex(target, band);
        float terminal = terminalResidual(costs, profiles[state], targetX, targetZ, finalGoal, floor);
        value[state] = terminal;
        queue.add(new QueueCell(state, terminal));
      }
    } else {
      int state = stateIndex(target, targetStratum);
      value[state] = 0F;
      queue.add(new QueueCell(state, 0F));
    }
    while (!queue.isEmpty()) {
      QueueCell state = queue.poll();
      if (state.value != value[state.index]) {
        continue;
      }
      int cell = cellIndex(state.index);
      int band = stratumIndex(state.index);
      int cx = minX + cell / zCount;
      int cz = minZ + cell % zCount;
      for (int[] d : NEIGHBORS) {
        int px = cx - d[0];
        int pz = cz - d[1];
        if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
          continue;
        }
        int predecessor = stateIndex(cellIndex(px, pz, minX, minZ, zCount), band);
        float candidate = state.value + horizontalEdgeCost(costs, profiles[predecessor], profiles[state.index], d[0], d[1], floor);
        if (candidate < value[predecessor]) {
          value[predecessor] = candidate;
          queue.add(new QueueCell(predecessor, candidate));
        }
      }
      if (band > 0) {
        int predecessor = stateIndex(cell, band - 1);
        float candidate = state.value + verticalEdgeCost(costs, profiles[predecessor], profiles[state.index], true, floor);
        if (candidate < value[predecessor]) {
          value[predecessor] = candidate;
          queue.add(new QueueCell(predecessor, candidate));
        }
      }
      if (band + 1 < STRATA) {
        int predecessor = stateIndex(cell, band + 1);
        float candidate = state.value + verticalEdgeCost(costs, profiles[predecessor], profiles[state.index], false, floor);
        if (candidate < value[predecessor]) {
          value[predecessor] = candidate;
          queue.add(new QueueCell(predecessor, candidate));
        }
      }
    }
    return value;
  }

  private static int[] bestSuccessors(CalculationContext context, FarfieldCosts costs, FarfieldColumnProfile[] profiles, float[] values, int minX, int maxX, int minZ, int maxZ, boolean floor) {
    int zCount = maxZ - minZ + 1;
    int[] best = new int[values.length];
    Arrays.fill(best, -1);
    for (int state = 0; state < values.length; state++) {
      int cell = cellIndex(state);
      int band = stratumIndex(state);
      int cx = minX + cell / zCount;
      int cz = minZ + cell % zCount;
      float bestValue = values[state];
      for (int[] d : NEIGHBORS) {
        int nx = cx + d[0];
        int nz = cz + d[1];
        if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
          continue;
        }
        int next = stateIndex(cellIndex(nx, nz, minX, minZ, zCount), band);
        float candidate = horizontalEdgeCost(costs, profiles[state], profiles[next], d[0], d[1], floor) + values[next];
        if (candidate < bestValue) {
          bestValue = candidate;
          best[state] = next;
        }
      }
      if (band > 0) {
        int next = stateIndex(cell, band - 1);
        float candidate = verticalEdgeCost(costs, profiles[state], profiles[next], false, floor) + values[next];
        if (candidate < bestValue) {
          bestValue = candidate;
          best[state] = next;
        }
      }
      if (band + 1 < STRATA) {
        int next = stateIndex(cell, band + 1);
        float candidate = verticalEdgeCost(costs, profiles[state], profiles[next], true, floor) + values[next];
        if (candidate < bestValue) {
          bestValue = candidate;
          best[state] = next;
        }
      }
    }
    return best;
  }

  private static float terminalResidual(FarfieldCosts costs, FarfieldColumnProfile targetProfile, int targetX, int targetZ, BlockPos finalGoal, boolean floor) {
    int centerX = targetX * FarfieldColumnKey.CELL_BLOCKS + (FarfieldColumnKey.CELL_BLOCKS >>> 1);
    int centerZ = targetZ * FarfieldColumnKey.CELL_BLOCKS + (FarfieldColumnKey.CELL_BLOCKS >>> 1);
    float tpb = floor ? costs.floorTicksPerBlock() : costs.cost(targetProfile).expectedTicksPerBlock();
    return (float) (Math.hypot(finalGoal.getX() - centerX, finalGoal.getZ() - centerZ) * tpb);
  }

  private static float horizontalEdgeCost(FarfieldCosts costs, FarfieldColumnProfile source, FarfieldColumnProfile dest, int dx, int dz, boolean floor) {
    float dist = (float) (FarfieldColumnKey.CELL_BLOCKS * Math.hypot(dx, dz));
    FarfieldCosts.FarfieldCost a = costs.cost(source);
    FarfieldCosts.FarfieldCost b = costs.cost(dest);
    float tpb = floor ? Math.min(a.floorTicksPerBlock(), b.floorTicksPerBlock()) : (a.expectedTicksPerBlock() + b.expectedTicksPerBlock()) * 0.5F;
    return dist * Math.max(0F, tpb);
  }

  private static float verticalEdgeCost(FarfieldCosts costs, FarfieldColumnProfile source, FarfieldColumnProfile dest, boolean up, boolean floor) {
    float distance = Math.max(1F, Math.abs(dest.representativeY() - source.representativeY()));
    float tpb = floor ? costs.floorTicksPerBlock() : up ? costs.verticalUpTicksPerBlock() : costs.verticalDownTicksPerBlock();
    FarfieldCosts.FarfieldCost a = costs.cost(source);
    FarfieldCosts.FarfieldCost b = costs.cost(dest);
    float terrain = floor ? Math.min(a.floorTicksPerBlock(), b.floorTicksPerBlock()) : Math.max(a.expectedTicksPerBlock(), b.expectedTicksPerBlock()) * 0.25F;
    return distance * Math.max(0F, tpb + terrain);
  }

  private static BetterBlockPos center(int cellX, int cellZ, int y) {
    return new BetterBlockPos(cellX * FarfieldColumnKey.CELL_BLOCKS + (FarfieldColumnKey.CELL_BLOCKS >>> 1), y, cellZ * FarfieldColumnKey.CELL_BLOCKS + (FarfieldColumnKey.CELL_BLOCKS >>> 1));
  }

  private static int stratum(CalculationContext context, int y) {
    int[] cuts = context.world.dimension() == Level.NETHER ? NETHER_STRATUM_CUTS : OVERWORLD_STRATUM_CUTS;
    return y < cuts[0] ? 0 : y < cuts[1] ? 1 : y < cuts[2] ? 2 : 3;
  }

  private static boolean stratumIntersects(CalculationContext context, int stratum, int yMin, int yMax) {
    int minY = context.world.getMinY();
    int maxY = context.world.getMaxY() - 1;
    int[] cuts = context.world.dimension() == Level.NETHER ? NETHER_STRATUM_CUTS : OVERWORLD_STRATUM_CUTS;
    int lo = switch (stratum) {
      case 0 -> minY;
      case 1 -> cuts[0];
      case 2 -> cuts[1];
      case 3 -> cuts[2];
      default -> throw new IllegalArgumentException("bad Farfield stratum: " + stratum);
    };
    int hi = switch (stratum) {
      case 0 -> cuts[0] - 1;
      case 1 -> cuts[1] - 1;
      case 2 -> cuts[2] - 1;
      case 3 -> maxY;
      default -> throw new IllegalArgumentException("bad Farfield stratum: " + stratum);
    };
    return Math.max(lo, yMin) <= Math.min(hi, yMax);
  }

  private static int representativeY(CalculationContext context, int stratum) {
    int minY = context.world.getMinY();
    int maxY = context.world.getMaxY() - 1;
    int[] cuts = context.world.dimension() == Level.NETHER ? NETHER_STRATUM_CUTS : OVERWORLD_STRATUM_CUTS;
    int lo = switch (stratum) {
      case 0 -> minY;
      case 1 -> cuts[0];
      case 2 -> cuts[1];
      case 3 -> cuts[2];
      default -> throw new IllegalArgumentException("bad Farfield stratum: " + stratum);
    };
    int hi = switch (stratum) {
      case 0 -> cuts[0] - 1;
      case 1 -> cuts[1] - 1;
      case 2 -> cuts[2] - 1;
      case 3 -> maxY;
      default -> throw new IllegalArgumentException("bad Farfield stratum: " + stratum);
    };
    lo = Math.max(minY, lo);
    hi = Math.min(maxY, hi);
    return lo > hi ? Math.max(minY, Math.min(maxY, cuts[Math.min(2, stratum)])) : (lo + hi) / 2;
  }

  private record QueueCell(int index, float value) implements Comparable<QueueCell> {
    @Override
    public int compareTo(QueueCell other) {
      return Float.compare(value, other.value);
    }
  }

  private record ColumnScan(int[] supports, int[] lava, int[] solid) {
  }
}
