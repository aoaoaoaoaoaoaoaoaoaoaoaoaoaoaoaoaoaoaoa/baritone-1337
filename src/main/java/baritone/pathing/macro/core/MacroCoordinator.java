package baritone.pathing.macro.core;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.route.RoutePlan;
import java.util.Optional;

public final class MacroCoordinator {
  private MacroCoordinator() {
  }

  public static Optional<MacroDirective> plan(MacroNavigator navigator, CalculationContext context, BetterBlockPos start, Goal goal) {
    if (MacroGoals.destinationChunkLoaded(context, goal)) {
      return Optional.empty();
    }
    Optional<MacroPlan> multimodal = MacroPlanner.plan(context, start, goal);
    if (multimodal.filter(plan -> plan.surfaceTransitionActions() > 0 && usefulSurfacePlan(context, plan)).isPresent()) {
      MacroPlan plan = multimodal.get();
      Optional<RoutePlan> route = materialize(context, plan, null);
      if (route.isPresent()) {
        return Optional.of(MacroDirective.certified(plan, route.get()));
      }
      Helper.HELPER.logDebug("Macro surface plan not executable yet: " + MacroPlanMaterializer.diagnostic(context, plan));
      Optional<MacroDirective> factualPrefix = factualSurfacePrefix(context, start, goal);
      if (factualPrefix.isPresent()) {
        return factualPrefix;
      }
      Helper.HELPER.logDebug("Deferring speculative surface macro plan until a factual prefix exists");
      return Optional.of(MacroDirective.deferred(plan));
    }
    return navigator.plan(context, start, goal);
  }

  private static Optional<MacroDirective> factualSurfacePrefix(CalculationContext context, BetterBlockPos start, Goal goal) {
    Optional<MacroPlan> factual = MacroPlanner.factualSurfacePrefix(context, start, goal);
    if (factual.isEmpty()) {
      return Optional.empty();
    }
    MacroPlan plan = factual.get();
    Optional<RoutePlan> route = materialize(context, plan, null);
    if (route.isPresent()) {
      Helper.HELPER.logDebug("Using factual macro surface prefix: " + plan.sequence());
      return Optional.of(MacroDirective.certified(plan, route.get()));
    }
    Optional<Goal> factualSurfaceGoal = MacroPlanMaterializer.firstFactualSurfaceGoal(plan);
    if (factualSurfaceGoal.isPresent()) {
      Helper.HELPER.logDebug("Walking to factual macro surface entry: " + factualSurfaceGoal.get());
      return Optional.of(MacroDirective.localGoal(plan.withLocalGoal(factualSurfaceGoal.get())));
    }
    return Optional.empty();
  }

  private static boolean usefulSurfacePlan(CalculationContext context, MacroPlan plan) {
    if (MacroSurfaceSessions.firstUsefulStart(plan.actions(), plan.src(), plan.dest()) < 0) {
      return false;
    }
    if (plan.surfaceTransitionDistance() < MacroSurfaceSessions.MIN_USEFUL_DISTANCE) {
      return false;
    }
    if (context.waterTransport.boatAvailable() && plan.boatActions() > 0) {
      return true;
    }
    return !context.waterTransport.boatAvailable() && plan.surfaceTransitionDistance() >= MacroSurfaceSessions.MIN_USEFUL_DISTANCE;
  }

  public static Optional<RoutePlan> materialize(CalculationContext context, MacroPlan plan, IPath localPrefix) {
    return MacroPlanMaterializer.materialize(context, plan, localPrefix);
  }
}
