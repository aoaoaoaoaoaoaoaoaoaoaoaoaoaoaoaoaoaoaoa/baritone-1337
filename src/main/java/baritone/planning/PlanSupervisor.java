package baritone.planning;

import baritone.pathing.path.PathExecutor;
import baritone.pathing.movement.movements.MovementWaterLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PlanSupervisor {
  private long revision;
  private ExecutableLeg current;
  private ExecutableLeg next;
  private PathExecutor syncedCurrent;
  private PathExecutor syncedNext;
  private ObservationSnapshot observation;
  private final ArrayList<ExecutionFailure> failures = new ArrayList<>();

  public void observe(ObservationSnapshot observation) {
    this.observation = observation;
  }

  public void syncWalkExecutors(ResourceKey<Level> dimension, PathExecutor current, PathExecutor next) {
    if (syncedCurrent == current && syncedNext == next) {
      return;
    }
    syncedCurrent = current;
    syncedNext = next;
    this.current = leg(dimension, current);
    this.next = leg(dimension, next);
    revision++;
  }

  private static ExecutableLeg leg(ResourceKey<Level> dimension, PathExecutor executor) {
    if (executor == null) {
      return null;
    }
    return executor.currentMovement() instanceof MovementWaterLine water ? SurfaceLineLeg.of(dimension, executor, water) : WalkPathLeg.of(dimension, executor);
  }

  public void reportFailure(ExecutionFailure failure) {
    failures.add(failure);
    revision++;
  }

  public RoutePlan snapshot() {
    return new RoutePlan(new RouteRevision(revision), Optional.ofNullable(current), Optional.ofNullable(next), List.copyOf(failures));
  }

  public Optional<ObservationSnapshot> observation() {
    return Optional.ofNullable(observation);
  }
}
