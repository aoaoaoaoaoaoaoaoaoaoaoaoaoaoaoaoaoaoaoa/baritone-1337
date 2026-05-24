package baritone.pathing.farfield;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroTraversalProfile;
import baritone.pathing.movement.CalculationContext;
import java.util.Optional;

public final class FarfieldNavigator {
  public Optional<FarfieldObjective> objective(CalculationContext context, BetterBlockPos start, Goal goal, MacroTraversalProfile profile) {
    if (!Baritone.settings().farfieldPlanning.value || profile.horse()) {
      return Optional.empty();
    }
    return FarfieldSnapshot.build(context, start, goal).map(snapshot -> new FarfieldObjective(goal, snapshot, start));
  }
}
