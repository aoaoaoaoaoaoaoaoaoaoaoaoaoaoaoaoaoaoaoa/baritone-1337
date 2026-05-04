package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.transport.TransportLeg;
import baritone.pathing.transport.TransportTerminality;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public final class WaterLineKernel {
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
    boolean clear = trace(waterStart, waterEnd, profile.halfWidth(), (x, z) -> {
      if (!profile.mode().legal(context, x, waterStart.y, z)) {
        return false;
      }
      profile.mode().appendValidPositions(new BetterBlockPos(x, waterStart.y, z), valid);
      return true;
    });
    valid.add(dest);
    TransportLeg leg = new TransportLeg(profile.mode().transportMode(), src, waterStart, dest, terminal ? TransportTerminality.TERMINAL : TransportTerminality.TRANSIT);
    return clear ? Optional.of(new WaterLineSegment(leg, waterEnd, profile, length, profile.cost(length), List.copyOf(valid))) : Optional.empty();
  }

  private static boolean trace(BetterBlockPos src, BetterBlockPos dest, double halfWidth, CellPredicate consumer) {
    LinkedHashSet<Cell> centerline = new LinkedHashSet<>();
    dda(src.x + 0.5D, src.z + 0.5D, dest.x + 0.5D, dest.z + 0.5D, centerline::add);
    LinkedHashSet<Cell> swept = new LinkedHashSet<>();
    int radius = (int) Math.ceil(halfWidth + 1D);
    for (Cell center : centerline) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          int x = center.x + dx;
          int z = center.z + dz;
          if (segmentIntersectsExpandedCell(src, dest, x, z, halfWidth)) {
            swept.add(new Cell(x, z));
          }
        }
      }
    }
    ArrayList<Cell> ordered = new ArrayList<>(swept);
    ordered.sort((a, b) -> Double.compare(projection(src, dest, a), projection(src, dest, b)));
    for (Cell cell : ordered) {
      if (!consumer.test(cell.x, cell.z)) {
        return false;
      }
    }
    return true;
  }

  private static void dda(double sx, double sz, double ex, double ez, CellConsumer consumer) {
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

    consumer.accept(new Cell(x, z));
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
      consumer.accept(new Cell(x, z));
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

  private static double projection(BetterBlockPos src, BetterBlockPos dest, Cell cell) {
    double sx = src.x + 0.5D;
    double sz = src.z + 0.5D;
    double dx = dest.x - src.x;
    double dz = dest.z - src.z;
    double lenSq = dx * dx + dz * dz;
    return ((cell.x + 0.5D - sx) * dx + (cell.z + 0.5D - sz) * dz) / lenSq;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }

  private record Cell(int x, int z) {
  }

  @FunctionalInterface
  private interface CellConsumer {
    void accept(Cell cell);
  }

  @FunctionalInterface
  private interface CellPredicate {
    boolean test(int x, int z);
  }
}
