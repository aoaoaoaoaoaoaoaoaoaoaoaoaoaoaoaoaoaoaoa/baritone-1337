package baritone.pathing.mounted;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.util.Mth;

final class HorseCruiseRayCertifier {
  private static final double LATTICE_EPSILON = 1.0E-7D;
  private static final double POSE_EPSILON = 1.0E-6D;
  private static final int FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS = 2;

  private final CalculationContext context;
  private final HorseCollisionOracle oracle;
  private final double halfWidth;
  private final double ticksPerBlock;
  private final int stepRiseBlocks;
  private final int samplesPerBlock;
  private final double hazardMargin;
  private final double poorSupportPenaltyTicks;
  private final double diagonalStepOverrunPenaltyTicks;
  private final int minSupportQuadrants;

  HorseCruiseRayCertifier(CalculationContext context, HorseCollisionOracle oracle, double halfWidth, double ticksPerBlock, int stepRiseBlocks, int samplesPerBlock, double hazardMargin,
    double poorSupportPenaltyTicks, double diagonalStepOverrunPenaltyTicks, int minSupportQuadrants) {
    this.context = context;
    this.oracle = oracle;
    this.halfWidth = halfWidth;
    this.ticksPerBlock = ticksPerBlock;
    this.stepRiseBlocks = stepRiseBlocks;
    this.samplesPerBlock = samplesPerBlock;
    this.hazardMargin = hazardMargin;
    this.poorSupportPenaltyTicks = poorSupportPenaltyTicks;
    this.diagonalStepOverrunPenaltyTicks = diagonalStepOverrunPenaltyTicks;
    this.minSupportQuadrants = minSupportQuadrants;
  }

  CertifiedCruise certify(HorsePath.Waypoint from, HorsePath.Waypoint to) {
    return certify(Pose.of(from), Pose.of(to));
  }

  private CertifiedCruise certify(Pose from, Pose to) {
    if (samePose(from, to) || !cornerLattice(from.centerX, from.centerZ) || !cornerLattice(to.centerX, to.centerZ) || horseWaterborne(from) || horseWaterborne(to)
      || !hasHullPathingData(from.centerX, from.centerZ) || !hasHullPathingData(to.centerX, to.centerZ)) {
      return null;
    }
    int dx = latticeDelta(from.centerX, to.centerX);
    int dz = latticeDelta(from.centerZ, to.centerZ);
    if (dx == 0 && dz == 0) {
      return null;
    }
    double sx = from.centerX;
    double sz = from.centerZ;
    double ex = to.centerX;
    double ez = to.centerZ;
    double distance = Math.hypot(ex - sx, ez - sz);
    int samples = Math.max(2, (int) Math.ceil(distance * samplesPerBlock));
    double cost = 0D;
    int y = from.pos.y;
    int minY = y;
    int maxY = y;
    double x = sx;
    double z = sz;
    for (int i = 1; i <= samples; i++) {
      double t = i / (double) samples;
      double nx = Mth.lerp(t, sx, ex);
      double nz = Mth.lerp(t, sz, ez);
      int ny;
      if (i == samples) {
        ny = to.pos.y;
        if (!transitionClear(x, y, z, nx, ny, nz)) {
          return null;
        }
      } else {
        ny = sampleY(x, y, z, nx, nz, to.pos.y);
        if (ny == Integer.MIN_VALUE) {
          return null;
        }
      }
      cost += Math.hypot(nx - x, nz - z) * ticksPerBlock;
      x = nx;
      z = nz;
      y = ny;
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    double overrunPenalty = diagonalStepOverrunPenalty(from, to, dx, dz, maxY);
    if (Double.isInfinite(overrunPenalty)) {
      return null;
    }
    return new CertifiedCruise(new HorsePath.CruiseVector(dx, dz), Math.max(1D, cost + poorSupportPenalty(to) + overrunPenalty), minY, maxY);
  }

  private int sampleY(double sx, int sy, double sz, double ex, double ez, int targetY) {
    int bestY = Integer.MIN_VALUE;
    int bestScore = Integer.MAX_VALUE;
    for (int dy = -stepRiseBlocks; dy <= stepRiseBlocks; dy++) {
      int y = sy + dy;
      if (!transitionClear(sx, sy, sz, ex, y, ez)) {
        continue;
      }
      int score = Math.abs(y - targetY) * 8 + Math.abs(dy);
      if (score < bestScore) {
        bestScore = score;
        bestY = y;
      }
    }
    return bestY;
  }

  private boolean transitionClear(double sx, int sy, double sz, double ex, int ey, double ez) {
    if (!hasHullPathingData(ex, ez) || horseWaterborne(ex, ey, ez)) {
      return false;
    }
    int rise = ey - sy;
    if (Math.abs(rise) > stepRiseBlocks) {
      return false;
    }
    HorseCollisionOracle.Move move = oracle.clippedStep(sx, sy, sz, ex - sx, ez - sz, true);
    if (!move.reached(ex - sx, ez - sz)) {
      return false;
    }
    if (rise > 0 && Math.abs(move.dy() - rise) >= 0.2D || rise <= 0 && Math.abs(move.dy()) >= 0.2D) {
      return false;
    }
    if (rise < 0 && !oracle.clearSegment(ex, sy, ez, ex, ey, ez)) {
      return false;
    }
    if (!oracle.clearHazardSegment(sx, sy, sz, ex, ey, ez, hazardMargin)) {
      return false;
    }
    return oracle.standable(ex, ey, ez) && oracle.groundSupportQuadrants(ex, ey, ez) >= minSupportQuadrants;
  }

  private double poorSupportPenalty(Pose landing) {
    int missing = 4 - oracle.groundSupportQuadrants(landing.centerX, landing.pos.y, landing.centerZ);
    return missing <= 0 ? 0D : poorSupportPenaltyTicks * missing * (missing + 1D) * 0.5D;
  }

  private double diagonalStepOverrunPenalty(Pose from, Pose to, int dx, int dz, int promotedY) {
    if (dx == 0 || dz == 0 || promotedY <= from.pos.y) {
      return 0D;
    }
    return switch (supportDropBlocks(to.centerX + Integer.signum(dx), promotedY, to.centerZ + Integer.signum(dz))) {
      case 0 -> 0D;
      case 1 -> diagonalStepOverrunPenaltyTicks;
      default -> Double.POSITIVE_INFINITY;
    };
  }

  private int supportDropBlocks(double centerX, int feetY, double centerZ) {
    if (!hasHullPathingData(centerX, centerZ)) {
      return 0;
    }
    for (int drop = 0; drop < FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS; drop++) {
      int y = feetY - drop;
      if (oracle.groundSupportQuadrants(centerX, y, centerZ) > 0 || oracle.waterSupport(centerX, y, centerZ)) {
        return drop;
      }
    }
    return FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS;
  }

  private boolean hasHullPathingData(double centerX, double centerZ) {
    int minX = Mth.floor(centerX - halfWidth + LATTICE_EPSILON);
    int maxX = Mth.floor(centerX + halfWidth - LATTICE_EPSILON);
    int minZ = Mth.floor(centerZ - halfWidth + LATTICE_EPSILON);
    int maxZ = Mth.floor(centerZ + halfWidth - LATTICE_EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (!context.worldBorder.entirelyContains(x, z) || !context.hasPathingData(x, z)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean horseWaterborne(Pose pose) {
    return horseWaterborne(pose.centerX, pose.pos.y, pose.centerZ);
  }

  private boolean horseWaterborne(double centerX, int feetY, double centerZ) {
    return oracle.wetHull(centerX, feetY, centerZ) || oracle.surfaceWaterSupport(centerX, feetY, centerZ);
  }

  private static boolean samePose(Pose from, Pose to) {
    return from.pos.equals(to.pos) && Math.abs(from.centerX - to.centerX) < POSE_EPSILON && Math.abs(from.centerZ - to.centerZ) < POSE_EPSILON;
  }

  private static boolean cornerLattice(double x, double z) {
    return cornerLattice(x) && cornerLattice(z);
  }

  private static boolean cornerLattice(double value) {
    return Math.abs(value - Math.rint(value)) < LATTICE_EPSILON;
  }

  private static int latticeDelta(double from, double to) {
    return (int) Math.round(to - from);
  }

  record Pose(BetterBlockPos pos, double centerX, double centerZ) {
    private static Pose of(HorsePath.Waypoint waypoint) {
      return new Pose(waypoint.pos(), waypoint.centerX(), waypoint.centerZ());
    }
  }

  record CertifiedCruise(HorsePath.CruiseVector vector, double costTicks, int minY, int maxY) {
    HorsePath.Cruise edge() {
      return new HorsePath.Cruise(vector, costTicks, minY, maxY);
    }
  }
}
