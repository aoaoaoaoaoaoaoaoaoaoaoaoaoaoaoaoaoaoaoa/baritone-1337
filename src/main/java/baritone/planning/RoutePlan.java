package baritone.planning;

import java.util.List;
import java.util.Optional;

public record RoutePlan(RouteRevision revision, Optional<ExecutableLeg> current, Optional<ExecutableLeg> next, List<ExecutionFailure> failures) {
  public static final RoutePlan EMPTY = new RoutePlan(RouteRevision.ZERO, Optional.empty(), Optional.empty(), List.of());

  public RoutePlan {
    failures = List.copyOf(failures);
  }
}
