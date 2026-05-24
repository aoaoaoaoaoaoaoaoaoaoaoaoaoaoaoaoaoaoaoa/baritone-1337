package baritone.pathing.farfield;

import baritone.Baritone;
import baritone.pathing.movement.CalculationContext;

public record FarfieldCosts(float openSurfaceTicksPerBlock, float voidTicksPerBlock, float lavaTicksPerBlock, float solidTicksPerBlock, float unknownTicksPerBlock, float floorTicksPerBlock,
  float verticalUpTicksPerBlock, float verticalDownTicksPerBlock, float endUnknownVoidProbability, float netherUnknownLavaProbability, float netherUnknownSolidProbability) {

  public static FarfieldCosts configured(CalculationContext context) {
    var settings = Baritone.settings();
    return new FarfieldCosts(finite(settings.farfieldOpenSurfaceTicksPerBlock.value, 8D), finite(settings.farfieldVoidTicksPerBlock.value, 48D), finite(settings.farfieldLavaTicksPerBlock.value, 36D),
      finite(settings.farfieldSolidTicksPerBlock.value, 96D), finite(settings.farfieldUnknownTicksPerBlock.value, 24D), finite(settings.costHeuristic.value, 3.563D),
      finite(settings.farfieldVerticalUpTicksPerBlock.value, 12D), finite(settings.farfieldVerticalDownTicksPerBlock.value, 6D), probability(settings.farfieldEndUnknownVoidProbability.value, 0.90D),
      probability(settings.farfieldNetherUnknownLavaProbability.value, 0.25D), probability(settings.farfieldNetherUnknownSolidProbability.value, 0.15D));
  }

  FarfieldCost cost(FarfieldColumnProfile profile) {
    float expected =
      (profile.openSurface255() * openSurfaceTicksPerBlock + profile.void255() * voidTicksPerBlock + profile.lava255() * lavaTicksPerBlock + profile.solid255() * solidTicksPerBlock) / 255F;
    expected *= heightMultiplier(profile);
    float floor = Math.min(expected, Math.max(0F, floorTicksPerBlock));
    return new FarfieldCost(expected, floor);
  }

  private static float heightMultiplier(FarfieldColumnProfile profile) {
    if (profile.evidence().factual()) {
      return 1F;
    }
    if (profile.solid255() == 255) {
      return 1F;
    }
    int y = profile.representativeY();
    if (y < 32) {
      return 1.25F;
    }
    if (y > 128) {
      return 1.35F;
    }
    if (y > 96) {
      return 1.10F;
    }
    return 1F;
  }

  private static float finite(double value, double fallback) {
    return (float) (Double.isFinite(value) && value >= 0D ? value : fallback);
  }

  private static float probability(double value, double fallback) {
    return (float) Math.max(0D, Math.min(1D, Double.isFinite(value) ? value : fallback));
  }

  public record FarfieldCost(float expectedTicksPerBlock, float floorTicksPerBlock) {
  }
}
