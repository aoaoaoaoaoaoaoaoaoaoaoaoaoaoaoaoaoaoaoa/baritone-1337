package baritone.pathing.calc;

import baritone.api.utils.BetterBlockPos;
import java.util.List;

public record PlanningProbe(List<BetterBlockPos> bestPath, List<BetterBlockPos> recentPath, BetterBlockPos recentNode) {
  public PlanningProbe {
    bestPath = bestPath == null ? List.of() : List.copyOf(bestPath);
    recentPath = recentPath == null ? List.of() : List.copyOf(recentPath);
  }

  public static PlanningProbe best(List<BetterBlockPos> path) {
    BetterBlockPos recent = path == null || path.isEmpty() ? null : path.getLast();
    return new PlanningProbe(path, List.of(), recent);
  }
}
