package baritone.pathing.movement;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

import baritone.api.utils.TrailReversibilityMode;

public record TrailReversibilityPolicy(TrailReversibilityMode mode, double suspectPenalty, double irreversiblePenalty) {
  public TrailReversibilityPolicy {
    if (mode == null) {
      mode = TrailReversibilityMode.OFF;
    }
    suspectPenalty = finiteNonnegative(suspectPenalty);
    irreversiblePenalty = finiteNonnegative(irreversiblePenalty);
  }

  public boolean enabled() {
    return mode != TrailReversibilityMode.OFF;
  }

  public double recost(TrailReversibility reversibility, double baseCost) {
    if (baseCost >= COST_INF || mode == TrailReversibilityMode.OFF) {
      return baseCost;
    }
    return switch (reversibility) {
      case INTRINSIC, CONSTRUCTIVE -> baseCost;
      case SUSPECT -> mode == TrailReversibilityMode.REQUIRE ? COST_INF : baseCost + suspectPenalty;
      case IRREVERSIBLE -> mode == TrailReversibilityMode.REQUIRE ? COST_INF : baseCost + irreversiblePenalty;
    };
  }

  public boolean admits(TrailReversibility reversibility) {
    return recost(reversibility, 0D) < COST_INF;
  }

  private static double finiteNonnegative(double value) {
    if (!Double.isFinite(value)) {
      return 0D;
    }
    return Math.max(0D, value);
  }
}
