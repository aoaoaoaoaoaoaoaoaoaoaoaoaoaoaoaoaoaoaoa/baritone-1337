package baritone.planning;

import baritone.api.pathing.calc.IPath;
import baritone.pathing.path.PathExecutor;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record WalkPathLeg(LegId id, IPath path, PathExecutor executor, StableBoundary start, StableBoundary end, SafetyContract safety, ProgressContract progress) implements ExecutableLeg {
  public static WalkPathLeg of(ResourceKey<Level> dimension, PathExecutor executor) {
    IPath path = executor.getPath();
    PlannedLocomotion.Pedestrian pedestrian = new PlannedLocomotion.Pedestrian();
    return new WalkPathLeg(new LegId(System.identityHashCode(executor)), path, executor, new StableBoundary(WorldCell.of(dimension, path.getSrc()), pedestrian),
      new StableBoundary(WorldCell.of(dimension, path.getDest()), pedestrian), SafetyContract.NONE, new ProgressContract(new ProgressMetric.PathIndex(), StallPolicy.NONE, TimeoutPolicy.NONE));
  }

  @Override
  public Optional<SplicePoint> splicePoint(ObservationSnapshot observation) {
    return executor.containsPathPosition(observation.feet().pos()) ? Optional.of(new SplicePoint(executor.getPosition(), new StableBoundary(observation.feet(), new PlannedLocomotion.Pedestrian())))
      : Optional.empty();
  }
}
