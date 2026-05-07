package baritone.pathing.route;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public record RoutePlan(List<RouteLeg> legs, BetterBlockPos src, BetterBlockPos dest, double estimatedTicks, double estimatedContinuationTicks, PlannedTransportState startState,
  PlannedTransportState endState) {
  public RoutePlan {
    legs = List.copyOf(legs);
    if (legs.isEmpty()) {
      throw new IllegalArgumentException("RoutePlan needs at least one leg");
    }
    if (!Double.isNaN(estimatedContinuationTicks) && (!Double.isFinite(estimatedContinuationTicks) || estimatedContinuationTicks < 0D)) {
      throw new IllegalArgumentException("route continuation must be nonnegative finite or NaN: " + estimatedContinuationTicks);
    }
  }

  public static RoutePlan legacy(IPath path, PlannedTransportState state) {
    return of(List.of(PathRouteLeg.legacy(path, state)), state, state);
  }

  public static RoutePlan of(List<RouteLeg> legs, PlannedTransportState startState, PlannedTransportState endState) {
    return of(legs, startState, endState, Double.NaN);
  }

  public static RoutePlan of(List<RouteLeg> legs, PlannedTransportState startState, PlannedTransportState endState, double estimatedContinuationTicks) {
    double ticks = 0D;
    for (RouteLeg leg : legs) {
      ticks += leg.estimatedTicks();
    }
    return new RoutePlan(legs, legs.get(0).src(), legs.get(legs.size() - 1).dest(), ticks, estimatedContinuationTicks, startState, endState);
  }

  public RoutePlan suffix(int firstLeg) {
    if (firstLeg <= 0) {
      return this;
    }
    if (firstLeg >= legs.size()) {
      throw new IllegalArgumentException("route suffix needs at least one leg");
    }
    List<RouteLeg> suffix = legs.subList(firstLeg, legs.size());
    return of(suffix, suffix.get(0).entryState(), endState, estimatedContinuationTicks);
  }

  public boolean contains(BlockPos feet) {
    for (RouteLeg leg : legs) {
      if (leg.contains(feet)) {
        return true;
      }
    }
    return false;
  }

  public Optional<IPath> soleLegacyPath() {
    return legs.size() == 1 && legs.get(0) instanceof PathRouteLeg leg && leg.surfaceOverlay() ? Optional.of(leg.path()) : Optional.empty();
  }
}
