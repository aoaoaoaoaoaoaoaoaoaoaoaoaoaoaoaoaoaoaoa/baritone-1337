package baritone.pathing.macro.core;

import baritone.api.utils.BetterBlockPos;
import java.util.List;

final class MacroSurfaceSessions {
  static final double MIN_USEFUL_DISTANCE = 24D;
  private static final double MIN_USEFUL_PROGRESS = 8D;

  private MacroSurfaceSessions() {
  }

  static int firstUsefulEnter(List<MacroActionInstance> actions, BetterBlockPos start, BetterBlockPos goal) {
    for (int i = 0; i < actions.size(); i++) {
      MacroSurfaceTransition transition = actions.get(i).surfaceTransition();
      if (transition != null && transition.stage() == MacroSurfaceTransitionStage.ENTER && completeRunDistance(actions, i) >= MIN_USEFUL_DISTANCE && runProgress(actions, i, start, goal) >= MIN_USEFUL_PROGRESS) {
        return i;
      }
    }
    return -1;
  }

  static int firstUsefulStart(List<MacroActionInstance> actions, BetterBlockPos start, BetterBlockPos goal) {
    for (int i = 0; i < actions.size(); i++) {
      MacroSurfaceTransition transition = actions.get(i).surfaceTransition();
      if (transition != null && (transition.stage() == MacroSurfaceTransitionStage.ENTER || transition.stage() == MacroSurfaceTransitionStage.TRANSIT) && runDistance(actions, i) >= MIN_USEFUL_DISTANCE
        && runProgress(actions, i, start, goal) >= MIN_USEFUL_PROGRESS) {
        return i;
      }
    }
    return -1;
  }

  static double completeRunDistance(List<MacroActionInstance> actions, int enterIndex) {
    MacroSurfaceTransition enter = actions.get(enterIndex).surfaceTransition();
    if (enter == null || enter.stage() != MacroSurfaceTransitionStage.ENTER) {
      return 0D;
    }
    return runDistance(actions, enterIndex);
  }

  static double runDistance(List<MacroActionInstance> actions, int startIndex) {
    MacroSurfaceTransition start = actions.get(startIndex).surfaceTransition();
    if (start == null || start.stage() != MacroSurfaceTransitionStage.ENTER && start.stage() != MacroSurfaceTransitionStage.TRANSIT) {
      return 0D;
    }
    double distance = 0D;
    int firstTransit = start.stage() == MacroSurfaceTransitionStage.TRANSIT ? startIndex : startIndex + 1;
    for (int i = firstTransit; i < actions.size(); i++) {
      MacroSurfaceTransition transition = actions.get(i).surfaceTransition();
      if (transition == null || transition.mode() != start.mode() || transition.componentId() != start.componentId()) {
        return 0D;
      }
      switch (transition.stage()) {
        case TRANSIT -> distance += transition.distance();
        case EXIT -> {
          return distance;
        }
        case ENTER -> {
          return 0D;
        }
      }
    }
    return distance;
  }

  private static double runProgress(List<MacroActionInstance> actions, int startIndex, BetterBlockPos start, BetterBlockPos goal) {
    BetterBlockPos terminal = runTerminal(actions, startIndex);
    return terminal == null ? Double.NEGATIVE_INFINITY : flatDistance(start, goal) - flatDistance(terminal, goal);
  }

  private static BetterBlockPos runTerminal(List<MacroActionInstance> actions, int startIndex) {
    MacroSurfaceTransition start = actions.get(startIndex).surfaceTransition();
    if (start == null || start.stage() != MacroSurfaceTransitionStage.ENTER && start.stage() != MacroSurfaceTransitionStage.TRANSIT) {
      return null;
    }
    int firstTransit = start.stage() == MacroSurfaceTransitionStage.TRANSIT ? startIndex : startIndex + 1;
    BetterBlockPos terminal = start.waterEnd();
    for (int i = firstTransit; i < actions.size(); i++) {
      MacroSurfaceTransition transition = actions.get(i).surfaceTransition();
      if (transition == null || transition.mode() != start.mode() || transition.componentId() != start.componentId()) {
        return terminal;
      }
      terminal = transition.stage() == MacroSurfaceTransitionStage.EXIT && transition.dryEnd() != null ? transition.dryEnd() : transition.waterEnd();
      if (transition.stage() == MacroSurfaceTransitionStage.EXIT) {
        return terminal;
      }
      if (transition.stage() == MacroSurfaceTransitionStage.ENTER) {
        return null;
      }
    }
    return terminal;
  }

  private static double flatDistance(BetterBlockPos a, BetterBlockPos b) {
    return Math.hypot(a.x - b.x, a.z - b.z);
  }
}
