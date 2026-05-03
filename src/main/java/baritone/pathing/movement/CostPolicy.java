package baritone.pathing.movement;

public record CostPolicy(double breakBlockAdditional, double backtrackFavoringCoefficient, double jumpPenalty, double walkOnWaterOnePenalty, double waterWalkCost, double waterMoveCost) {

  public CostPolicy withBacktrackFavoringCoefficient(double coefficient) {
    return new CostPolicy(breakBlockAdditional, coefficient, jumpPenalty, walkOnWaterOnePenalty, waterWalkCost, waterMoveCost);
  }

  public CostPolicy withJumpPenalty(double penalty) {
    return new CostPolicy(breakBlockAdditional, backtrackFavoringCoefficient, penalty, walkOnWaterOnePenalty, waterWalkCost, waterMoveCost);
  }

  public double waterCost(boolean deepEnoughToSwim) {
    return deepEnoughToSwim ? waterMoveCost : waterWalkCost;
  }
}
