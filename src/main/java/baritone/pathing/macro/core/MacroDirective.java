package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.pathing.route.RoutePlan;
import java.util.Optional;

public record MacroDirective(Goal localGoal, MacroPlan plan, Optional<RoutePlan> certifiedRoute, boolean deferred) {
  public static MacroDirective localGoal(MacroPlan plan) {
    return new MacroDirective(plan.localGoal(), plan, Optional.empty(), false);
  }

  public static MacroDirective certified(MacroPlan plan, RoutePlan route) {
    return new MacroDirective(plan.localGoal(), plan, Optional.of(route), false);
  }

  public static MacroDirective deferred(MacroPlan plan) {
    return new MacroDirective(plan.localGoal(), plan, Optional.empty(), true);
  }
}
