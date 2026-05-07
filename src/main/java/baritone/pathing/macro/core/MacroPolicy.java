package baritone.pathing.macro.core;

import baritone.Baritone;

public record MacroPolicy(double timeWeight, double jumpWeight, double sprintWeight, double waterWeight, double damageHazardWeight, double failureHazardWeight, double uncertaintyWeight, int epoch) {
  public static MacroPolicy configured() {
    return new MacroPolicy(Baritone.settings().macroBiomeTimeWeight.value, Baritone.settings().macroBiomeJumpWeight.value, Baritone.settings().macroBiomeSprintWeight.value,
      Baritone.settings().macroBiomeWaterWeight.value, Baritone.settings().macroBiomeDamageRiskWeight.value, Baritone.settings().macroBiomeFailureRiskWeight.value,
      Baritone.settings().macroBiomeUncertaintyWeight.value, 0);
  }

  public double score(MacroCostVector cost, MacroAgentState before, MacroAgentState after) {
    return timeWeight * cost.timeTicks() + jumpWeight * cost.jumpTicks() + sprintWeight * cost.sprintTicks() + waterWeight * cost.waterExposureTicks() + damageHazardWeight * cost.damageHazard()
      + failureHazardWeight * cost.failureHazard() + uncertaintyWeight * cost.uncertainty();
  }
}
