package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.utils.BetterBlockPos;
import java.util.Optional;

public interface ActivePathCalculation extends IPathFinder {
  BetterBlockPos getStart();

  void cancel();

  void setPublicationSink(PathPublicationSink sink);

  default Optional<PlanningProbe> probe() {
    return Optional.empty();
  }

  @Override
  default Optional<IPath> pathToMostRecentNodeConsidered() {
    return Optional.empty();
  }
}
