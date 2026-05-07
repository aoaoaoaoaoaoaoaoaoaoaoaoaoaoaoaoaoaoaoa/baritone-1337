package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.transport.TransportLeg;
import baritone.pathing.transport.TransportTerminality;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public final class WaterLineKernel {
  private static final ThreadLocal<TraceScratch> SCRATCH = ThreadLocal.withInitial(TraceScratch::new);

  private WaterLineKernel() {
  }

  public static Optional<WaterLineSegment> connect(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, WaterLineProfile profile) {
    return connect(context, src, src, dest, profile);
  }

  public static Optional<WaterLineSegment> connect(CalculationContext context, BetterBlockPos src, BetterBlockPos waterStart, BetterBlockPos dest, WaterLineProfile profile) {
    return connect(context, src, waterStart, dest, profile, true);
  }

  public static Optional<WaterLineSegment> connect(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, WaterLineProfile profile, boolean terminal) {
    return connect(context, src, src, dest, profile, terminal);
  }

  public static Optional<WaterLineSegment> connect(CalculationContext context, BetterBlockPos src, BetterBlockPos waterStart, BetterBlockPos dest, WaterLineProfile profile, boolean terminal) {
    return connect(context, src, waterStart, dest, dest, profile, terminal);
  }

  public static Optional<WaterLineSegment> connect(CalculationContext context, BetterBlockPos src, BetterBlockPos waterStart, BetterBlockPos waterEnd, BetterBlockPos dest, WaterLineProfile profile,
    boolean terminal) {
    if (waterStart.y != waterEnd.y || waterStart.x == waterEnd.x && waterStart.z == waterEnd.z) {
      return Optional.empty();
    }
    double length = Math.hypot(waterEnd.x - waterStart.x, waterEnd.z - waterStart.z);
    if (length < profile.minimumBlocks()) {
      return Optional.empty();
    }
    LinkedHashSet<BetterBlockPos> valid = new LinkedHashSet<>();
    valid.add(src);
    boolean clear = traceCells(waterStart, waterEnd, profile.halfWidth(), (x, z, centerline) -> {
      if (!(centerline ? profile.mode().legal(context, x, waterStart.y, z) : profile.mode().legalHull(context, x, waterStart.y, z))) {
        return false;
      }
      if (centerline) {
        profile.mode().appendValidPositions(new BetterBlockPos(x, waterStart.y, z), valid);
      }
      return true;
    });
    valid.add(dest);
    TransportLeg leg = new TransportLeg(profile.mode().transportMode(), src, waterStart, dest, terminal ? TransportTerminality.TERMINAL : TransportTerminality.TRANSIT);
    return clear ? Optional.of(new WaterLineSegment(leg, waterEnd, profile, length, profile.cost(length), List.copyOf(valid))) : Optional.empty();
  }

  public static boolean traceCells(BetterBlockPos src, BetterBlockPos dest, double halfWidth, SweptCellPredicate consumer) {
    TraceScratch scratch = SCRATCH.get();
    if (scratch.active) {
      return traceCells(src, dest, halfWidth, consumer, new TraceScratch());
    }
    scratch.active = true;
    try {
      return traceCells(src, dest, halfWidth, consumer, scratch);
    } finally {
      scratch.clear();
      scratch.active = false;
    }
  }

  private static boolean traceCells(BetterBlockPos src, BetterBlockPos dest, double halfWidth, SweptCellPredicate consumer, TraceScratch scratch) {
    scratch.clear();
    int centerlineCapacity = Math.abs(dest.x - src.x) + Math.abs(dest.z - src.z) + 1;
    scratch.ensureCenterlineCapacity(centerlineCapacity);
    traceCenterline(src.x + 0.5D, src.z + 0.5D, dest.x + 0.5D, dest.z + 0.5D, scratch);
    int radius = (int) Math.ceil(halfWidth + 1D);
    int diameter = radius * 2 + 1;
    scratch.swept.ensureCapacity(scratch.centerline.size() * diameter * diameter);
    for (int i = 0; i < scratch.centerline.size(); i++) {
      long center = scratch.centerline.getLong(i);
      int cx = x(center);
      int cz = z(center);
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          int x = cx + dx;
          int z = cz + dz;
          long cell = pack(x, z);
          if (!segmentIntersectsExpandedCell(src, dest, x, z, halfWidth)) {
            continue;
          }
          if (scratch.swept.add(cell) && !consumer.test(x, z, scratch.centerlineCells.contains(cell))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static void traceCenterline(double sx, double sz, double ex, double ez, TraceScratch scratch) {
    int x = floor(sx);
    int z = floor(sz);
    int endX = floor(ex);
    int endZ = floor(ez);
    int stepX = Integer.compare(endX, x);
    int stepZ = Integer.compare(endZ, z);
    double dx = ex - sx;
    double dz = ez - sz;
    double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1D / dx);
    double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1D / dz);
    double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1D - sx : sx - x) / Math.abs(dx));
    double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1D - sz : sz - z) / Math.abs(dz));

    scratch.addCenterline(pack(x, z));
    while (x != endX || z != endZ) {
      if (tMaxX < tMaxZ) {
        x += stepX;
        tMaxX += tDeltaX;
      } else if (tMaxZ < tMaxX) {
        z += stepZ;
        tMaxZ += tDeltaZ;
      } else {
        x += stepX;
        z += stepZ;
        tMaxX += tDeltaX;
        tMaxZ += tDeltaZ;
      }
      scratch.addCenterline(pack(x, z));
    }
  }

  private static boolean segmentIntersectsExpandedCell(BetterBlockPos src, BetterBlockPos dest, int cellX, int cellZ, double halfWidth) {
    double minX = cellX - halfWidth;
    double maxX = cellX + 1D + halfWidth;
    double minZ = cellZ - halfWidth;
    double maxZ = cellZ + 1D + halfWidth;
    double startX = src.x + 0.5D;
    double startZ = src.z + 0.5D;
    double endX = dest.x + 0.5D;
    double endZ = dest.z + 0.5D;
    double tMin = 0D;
    double tMax = 1D;

    double deltaX = endX - startX;
    if (deltaX == 0D) {
      if (startX < minX || startX > maxX) {
        return false;
      }
    } else {
      double a = (minX - startX) / deltaX;
      double b = (maxX - startX) / deltaX;
      tMin = Math.max(tMin, Math.min(a, b));
      tMax = Math.min(tMax, Math.max(a, b));
      if (tMin > tMax) {
        return false;
      }
    }

    double deltaZ = endZ - startZ;
    if (deltaZ == 0D) {
      return startZ >= minZ && startZ <= maxZ;
    }
    double a = (minZ - startZ) / deltaZ;
    double b = (maxZ - startZ) / deltaZ;
    tMin = Math.max(tMin, Math.min(a, b));
    tMax = Math.min(tMax, Math.max(a, b));
    return tMin <= tMax;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }

  private static long pack(int x, int z) {
    return (long) x << 32 ^ z & 0xFFFFFFFFL;
  }

  private static int x(long packed) {
    return (int) (packed >> 32);
  }

  private static int z(long packed) {
    return (int) packed;
  }

  private static final class TraceScratch {
    final LongArrayList centerline = new LongArrayList();
    final LongOpenHashSet centerlineCells = new LongOpenHashSet();
    final LongOpenHashSet swept = new LongOpenHashSet();
    boolean active;

    void ensureCenterlineCapacity(int capacity) {
      centerline.ensureCapacity(capacity);
      centerlineCells.ensureCapacity(capacity);
    }

    void addCenterline(long cell) {
      centerline.add(cell);
      centerlineCells.add(cell);
    }

    void clear() {
      centerline.clear();
      centerlineCells.clear();
      swept.clear();
    }
  }

  @FunctionalInterface
  public interface SweptCellPredicate {
    boolean test(int x, int z, boolean centerline);
  }
}
