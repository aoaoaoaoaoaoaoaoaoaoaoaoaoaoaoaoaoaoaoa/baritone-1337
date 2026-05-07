package baritone.pathing.route;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.water.WaterLineSegment;
import java.util.List;
import net.minecraft.core.BlockPos;

public record SurfaceRouteLeg(WaterLineSegment segment, PlannedTransportState entryState, PlannedTransportState exitState, BoatRecoveryPolicy recoveryPolicy, int componentId) implements RouteLeg {
  private static final double SWIM_CORRIDOR_TOLERANCE_SQ = 9D;
  private static final double BOAT_CORRIDOR_TOLERANCE_SQ = 256D;
  private static final double SWIM_DRY_ACQUISITION_SOURCE_TOLERANCE_SQ = 9D;
  private static final double SWIM_WET_ACQUISITION_SOURCE_TOLERANCE_SQ = 9D;
  private static final double SWIM_ACQUISITION_TARGET_TOLERANCE_SQ = 256D;

  public SurfaceRouteLeg {
    if (componentId < 0) {
      throw new IllegalArgumentException("surface route legs are execution contracts and therefore need a factual water component");
    }
  }

  @Override
  public BetterBlockPos src() {
    return segment.src();
  }

  @Override
  public BetterBlockPos dest() {
    return segment.dest();
  }

  @Override
  public double estimatedTicks() {
    return segment.cost();
  }

  @Override
  public boolean contains(BlockPos feet) {
    long packed = feet.asLong();
    for (BetterBlockPos pos : segment.validPositions()) {
      if (pos.asLong() == packed) {
        return true;
      }
    }
    return src().asLong() == packed || dest().asLong() == packed;
  }

  public boolean acceptsCorridor(BlockPos feet, boolean wet) {
    if (contains(feet)) {
      return true;
    }
    if (!segment.boat() && acquisitionCorridorContains(feet, wet)) {
      return true;
    }
    if (!wet || Math.abs(feet.getY() - segment.waterStart().y) > 1) {
      return false;
    }
    BetterBlockPos start = segment.waterStart();
    BetterBlockPos end = segment.waterEnd();
    double ax = end.x - start.x;
    double az = end.z - start.z;
    double lenSq = ax * ax + az * az;
    if (lenSq <= 0D) {
      return false;
    }
    double px = feet.getX() - start.x;
    double pz = feet.getZ() - start.z;
    double progress = (px * ax + pz * az) / lenSq;
    if (progress < -0.25D || progress > 1.25D) {
      return false;
    }
    double cx = start.x + ax * progress;
    double cz = start.z + az * progress;
    double dx = feet.getX() - cx;
    double dz = feet.getZ() - cz;
    return dx * dx + dz * dz <= (segment.boat() ? BOAT_CORRIDOR_TOLERANCE_SQ : SWIM_CORRIDOR_TOLERANCE_SQ);
  }

  private boolean acquisitionCorridorContains(BlockPos feet, boolean wet) {
    if (Math.abs(feet.getY() - segment.src().y) > 1 || Math.abs(feet.getY() - segment.waterStart().y) > 2) {
      return false;
    }
    double sx = feet.getX() - segment.src().x;
    double sz = feet.getZ() - segment.src().z;
    double wx = feet.getX() - segment.waterStart().x;
    double wz = feet.getZ() - segment.waterStart().z;
    double sourceTolerance = wet ? SWIM_WET_ACQUISITION_SOURCE_TOLERANCE_SQ : SWIM_DRY_ACQUISITION_SOURCE_TOLERANCE_SQ;
    return sx * sx + sz * sz <= sourceTolerance && wx * wx + wz * wz <= SWIM_ACQUISITION_TARGET_TOLERANCE_SQ;
  }

  @Override
  public List<BetterBlockPos> validPositions() {
    return segment.validPositions();
  }

  @Override
  public List<BetterBlockPos> renderPositions() {
    return List.of(segment.waterStart(), segment.waterEnd());
  }

  public boolean terminal() {
    return segment.terminal();
  }
}
