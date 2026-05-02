package baritone.pathing.movement;

public record CostPolicy(double breakBlockAdditional, double backtrackFavoringCoefficient, double jumpPenalty, double walkOnWaterOnePenalty, double waterWalkSpeed) {

  public CostPolicy withBacktrackFavoringCoefficient(double coefficient) {
    return new CostPolicy(breakBlockAdditional, coefficient, jumpPenalty, walkOnWaterOnePenalty, waterWalkSpeed);
  }

  public CostPolicy withJumpPenalty(double penalty) {
    return new CostPolicy(breakBlockAdditional, backtrackFavoringCoefficient, penalty, walkOnWaterOnePenalty, waterWalkSpeed);
  }
}
