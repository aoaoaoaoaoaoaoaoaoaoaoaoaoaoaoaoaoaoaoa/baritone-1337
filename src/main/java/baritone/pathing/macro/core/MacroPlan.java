package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import java.util.List;

public record MacroPlan(BetterBlockPos src, BetterBlockPos dest, Goal localGoal, List<BetterBlockPos> renderPositions, List<MacroActionInstance> actions, MacroCostVector totalVector,
  double totalScore, int cellBlocks, int factualCells, int unknownCells, int liveCells, int cachedCells, int predictedCells, int priorCells, List<MacroPlanVertex> vertices, String sequence,
  String firstUncertifiedAction, MacroValueTelemetry valueTelemetry) {
  public MacroPlan {
    renderPositions = List.copyOf(renderPositions);
    actions = List.copyOf(actions);
    vertices = List.copyOf(vertices);
    valueTelemetry = valueTelemetry == null ? MacroValueTelemetry.EMPTY : valueTelemetry;
  }

  public int boatActions() {
    int count = 0;
    for (MacroActionInstance action : actions) {
      if (action.surfaceTransition() != null && action.surfaceTransition().boat()) {
        count++;
      }
    }
    return count;
  }

  public int portalActions() {
    int count = 0;
    for (MacroActionInstance action : actions) {
      if (action.kind().portal()) {
        count++;
      }
    }
    return count;
  }

  public int surfaceTransitionActions() {
    int count = 0;
    for (MacroActionInstance action : actions) {
      if (action.surfaceTransition() != null) {
        count++;
      }
    }
    return count;
  }

  public int swimActions() {
    int count = 0;
    for (MacroActionInstance action : actions) {
      if (action.surfaceTransition() != null && action.surfaceTransition().swim()) {
        count++;
      }
    }
    return count;
  }

  public double surfaceTransitionDistance() {
    double distance = 0D;
    for (MacroActionInstance action : actions) {
      if (action.surfaceTransition() != null) {
        distance += action.surfaceTransition().distance();
      }
    }
    return distance;
  }

  public double boatDistance() {
    double distance = 0D;
    for (MacroActionInstance action : actions) {
      if (action.surfaceTransition() != null && action.surfaceTransition().boat()) {
        distance += action.surfaceTransition().distance();
      }
    }
    return distance;
  }

  public MacroPlan withLocalGoal(Goal goal) {
    return new MacroPlan(src, dest, goal, renderPositions, actions, totalVector, totalScore, cellBlocks, factualCells, unknownCells, liveCells, cachedCells, predictedCells, priorCells, vertices,
      sequence, firstUncertifiedAction, valueTelemetry);
  }
}
