package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.pathing.route.RoutePlan;
import java.util.Objects;
import java.util.Optional;

public sealed interface MacroDirective permits MacroDirective.LocalGoal, MacroDirective.Certified, MacroDirective.Deferred {
  Goal localGoal();

  MacroPlan plan();

  default Optional<RoutePlan> certifiedRoute() {
    return Optional.empty();
  }

  default boolean deferred() {
    return false;
  }

  static MacroDirective localGoal(MacroPlan plan) {
    return new LocalGoal(plan);
  }

  static MacroDirective certified(MacroPlan plan, RoutePlan route) {
    return new Certified(plan, route);
  }

  static MacroDirective deferred(MacroPlan plan) {
    return new Deferred(plan);
  }

  record LocalGoal(MacroPlan plan) implements MacroDirective {
    public LocalGoal {
      Objects.requireNonNull(plan);
    }

    @Override
    public Goal localGoal() {
      return plan.localGoal();
    }
  }

  record Certified(MacroPlan plan, RoutePlan route) implements MacroDirective {
    public Certified {
      Objects.requireNonNull(plan);
      Objects.requireNonNull(route);
    }

    @Override
    public Goal localGoal() {
      return plan.localGoal();
    }

    @Override
    public Optional<RoutePlan> certifiedRoute() {
      return Optional.of(route);
    }
  }

  record Deferred(MacroPlan plan) implements MacroDirective {
    public Deferred {
      Objects.requireNonNull(plan);
    }

    @Override
    public Goal localGoal() {
      return plan.localGoal();
    }

    @Override
    public boolean deferred() {
      return true;
    }
  }
}
