package baritone.planning;

import baritone.pathing.movement.movements.MovementWaterLine;
import baritone.pathing.movement.water.WaterLineSegment;
import baritone.pathing.path.PathExecutor;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record SurfaceLineLeg(LegId id, WaterLineSegment segment, PathExecutor executor, StableBoundary start, StableBoundary end, SafetyContract safety, ProgressContract progress,
  ExitDisposition exit) implements ExecutableLeg {
  public static SurfaceLineLeg of(ResourceKey<Level> dimension, PathExecutor executor, MovementWaterLine movement) {
    WaterLineSegment segment = movement.segment();
    PlannedLocomotion startMode = new PlannedLocomotion.Pedestrian();
    PlannedLocomotion cruiseMode = segment.boat() ? new PlannedLocomotion.MountedBoat(BoatLease.UNCONFIRMED) : new PlannedLocomotion.SurfaceSwim();
    ExitDisposition exit = segment.boat() ? segment.terminal() ? ExitDisposition.DISMOUNT_AND_PICKUP_BOAT : ExitDisposition.STAY_IN_MODE
      : segment.terminal() ? ExitDisposition.STABILIZE_ON_FOOT : ExitDisposition.STAY_IN_MODE;
    PlannedLocomotion endMode = switch (exit) {
      case STAY_IN_MODE -> cruiseMode;
      case STABILIZE_IN_WATER -> new PlannedLocomotion.SurfaceSwim();
      case STABILIZE_ON_FOOT, DISMOUNT_AND_PICKUP_BOAT, DISMOUNT_AND_LEAVE_BOAT -> new PlannedLocomotion.Pedestrian();
    };
    SafetyContract safety = new SafetyContract(
      segment.boat() ? java.util.List.of(new SafetyInvariant.VehicleOwnership(VehicleKind.BOAT)) : java.util.List.of(new SafetyInvariant.AirReserve(60, RecoveryPolicy.EMERGENCY_STABILIZE)),
      RecoveryPolicy.REPLAN);
    ProgressContract progress = new ProgressContract(new ProgressMetric.LineProjection(WorldCell.of(dimension, segment.waterStart()), WorldCell.of(dimension, segment.waterEnd())),
      new StallPolicy(80, 0.05D), TimeoutPolicy.NONE);
    return new SurfaceLineLeg(new LegId(System.identityHashCode(movement)), segment, executor, new StableBoundary(WorldCell.of(dimension, segment.src()), startMode),
      new StableBoundary(WorldCell.of(dimension, segment.dest()), endMode), safety, progress, exit);
  }

  public SurfaceLineMode mode() {
    return segment.boat() ? SurfaceLineMode.BOAT : SurfaceLineMode.SWIM;
  }

  @Override
  public Optional<SplicePoint> splicePoint(ObservationSnapshot observation) {
    return executor.containsPathPosition(observation.feet().pos()) ? Optional.of(new SplicePoint(executor.getPosition(), new StableBoundary(observation.feet(), end.locomotion()))) : Optional.empty();
  }
}
