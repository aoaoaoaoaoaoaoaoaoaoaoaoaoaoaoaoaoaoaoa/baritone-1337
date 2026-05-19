package baritone.pathing.mounted;

import net.minecraft.util.Mth;

public final class HorseTrackCertifier {
  private HorseTrackCertifier() {
  }

  public static Plan certify(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks, double waterCostMultiplier,
    int samplesPerBlock) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, Support.CENTER);
  }

  public static Plan certifyHull(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks, double waterCostMultiplier,
    int samplesPerBlock) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, Support.HULL, false);
  }

  public static Plan certifyBalanced(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks, double waterCostMultiplier,
    int samplesPerBlock) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, Support.BALANCED, false);
  }

  public static Plan certifyHullFromStableSource(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks,
    double waterCostMultiplier, int samplesPerBlock) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, Support.HULL, true);
  }

  public static Plan certifyBalancedFromStableSource(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks,
    double waterCostMultiplier, int samplesPerBlock) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, Support.BALANCED, true);
  }

  private static Plan certify(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks, double waterCostMultiplier,
    int samplesPerBlock, Support support) {
    return certify(oracle, sx, sy, sz, ex, ey, ez, ticksPerBlock, stepRiseBlocks, waterCostMultiplier, samplesPerBlock, support, false);
  }

  private static Plan certify(HorseCollisionOracle oracle, double sx, int sy, double sz, double ex, int ey, double ez, double ticksPerBlock, int stepRiseBlocks, double waterCostMultiplier,
    int samplesPerBlock, Support support, boolean stableSource) {
    double distance = Math.hypot(ex - sx, ez - sz);
    if (distance < 1.0E-6D || !stableSource && !standable(oracle, support, sx, sy, sz) || !standable(oracle, support, ex, ey, ez)) {
      return null;
    }
    int samples = Math.max(2, (int) Math.ceil(distance * samplesPerBlock));
    double stepCostDistance = distance / samples;
    int[] ys = {sy};
    double[] costs = {0D};
    int[] mins = {sy};
    int[] maxs = {sy};
    int[] waterMasks = {waterMask(oracle.surfaceWaterSupport(sx, sy, sz))};
    int count = 1;
    for (int i = 1; i <= samples; i++) {
      double previousT = (i - 1) / (double) samples;
      double previousX = Mth.lerp(previousT, sx, ex);
      double previousZ = Mth.lerp(previousT, sz, ez);
      double t = i / (double) samples;
      double x = Mth.lerp(t, sx, ex);
      double z = Mth.lerp(t, sz, ez);
      boolean terminal = i == samples;
      int fanout = terminal ? 1 : 2 * stepRiseBlocks + 1;
      int[] nextYs = new int[Math.max(4, count * fanout)];
      double[] nextCosts = new double[nextYs.length];
      int[] nextMins = new int[nextYs.length];
      int[] nextMaxs = new int[nextYs.length];
      int[] nextWaterMasks = new int[nextYs.length];
      int nextCount = 0;
      for (int state = 0; state < count; state++) {
        int lo = terminal ? ey : ys[state] - stepRiseBlocks;
        int hi = terminal ? ey : ys[state] + stepRiseBlocks;
        for (int y = lo; y <= hi; y++) {
          if (Math.abs(y - ys[state]) > stepRiseBlocks || !transitionClear(oracle, support, previousX, ys[state], previousZ, x, y, z)) {
            continue;
          }
          boolean water = oracle.surfaceWaterSupport(x, y, z);
          double cost = costs[state] + stepCostDistance * ticksPerBlock * (water ? waterCostMultiplier : 1D);
          nextCount = offer(nextYs, nextCosts, nextMins, nextMaxs, nextWaterMasks, nextCount, y, cost, Math.min(mins[state], y), Math.max(maxs[state], y), waterMasks[state] | waterMask(water));
        }
      }
      if (nextCount == 0) {
        return null;
      }
      ys = nextYs;
      costs = nextCosts;
      mins = nextMins;
      maxs = nextMaxs;
      waterMasks = nextWaterMasks;
      count = nextCount;
    }
    int best = 0;
    for (int i = 1; i < count; i++) {
      if (costs[i] < costs[best]) {
        best = i;
      }
    }
    return new Plan(costs[best], mins[best], maxs[best], waterMasks[best] == 3);
  }

  private static boolean transitionClear(HorseCollisionOracle oracle, Support support, double sx, int sy, double sz, double ex, int ey, double ez) {
    int rise = ey - sy;
    if (rise > 0) {
      return oracle.clearSegment(sx, sy, sz, sx, ey, sz) && oracle.clearSegment(sx, ey, sz, ex, ey, ez) && standable(oracle, support, ex, ey, ez);
    }
    if (rise < 0) {
      return oracle.clearSegment(sx, sy, sz, ex, sy, ez) && oracle.clearSegment(ex, sy, ez, ex, ey, ez) && standable(oracle, support, ex, ey, ez);
    }
    return oracle.clearSegment(sx, sy, sz, ex, ey, ez) && standable(oracle, support, ex, ey, ez);
  }

  private static boolean standable(HorseCollisionOracle oracle, Support support, double x, int y, double z) {
    return switch (support) {
      case CENTER -> oracle.centerStandable(x, y, z);
      case BALANCED -> oracle.balancedStandable(x, y, z);
      case HULL -> oracle.standable(x, y, z);
    };
  }

  private static int offer(int[] ys, double[] costs, int[] mins, int[] maxs, int[] waterMasks, int count, int y, double cost, int minY, int maxY, int waterMask) {
    for (int i = 0; i < count; i++) {
      if (ys[i] == y) {
        if (cost < costs[i]) {
          costs[i] = cost;
          mins[i] = minY;
          maxs[i] = maxY;
          waterMasks[i] = waterMask;
        }
        return count;
      }
    }
    ys[count] = y;
    costs[count] = cost;
    mins[count] = minY;
    maxs[count] = maxY;
    waterMasks[count] = waterMask;
    return count + 1;
  }

  private static int waterMask(boolean water) {
    return water ? 2 : 1;
  }

  public record Plan(double costTicks, int minY, int maxY, boolean mixedWater) {
  }

  private enum Support {
    CENTER, BALANCED, HULL
  }
}
